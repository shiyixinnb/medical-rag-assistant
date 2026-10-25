package com.syx.demo.llm.domain;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.util.List;

/**
 * 双数据域向量库装配。
 *
 * <p><b>为什么不用 Spring AI 自动配置的那个单例 VectorStore？</b>两个原因：
 *
 * <ol>
 *   <li><b>自动配置的索引没有声明元数据字段。</b>M7 的
 *       {@code RedisVectorStoreAutoConfiguration} 只设了 indexName 和 prefix，
 *       生成的 RediSearch 索引只包含 content 和 embedding 两个字段。
 *       任何 {@code filterExpression("domain == 'PUBLIC'")} 都会因为"未知字段"直接报错 ——
 *       也就是说，光靠自动配置是做不了元数据过滤的。</li>
 *   <li><b>数据域隔离要靠物理隔离，不能靠过滤条件。</b>这里建两个索引、两个 key 前缀：
 *       <pre>
 *         公共域：索引 emergency_public_index   前缀 embedding:public:
 *         患者域：索引 emergency_patient_index  前缀 embedding:patient:
 *       </pre>
 *       未授权的请求根本不会去碰患者域那个索引，安全边界是"访问路径"而不是"查询条件"。
 *       患者域内部再叠一层 tenantId 的 TAG 过滤 + Java 侧复核，构成多租户的纵深防御。</li>
 * </ol>
 *
 * <p>公共域的 Bean 标了 {@code @Primary}，这样原有的 {@code VectorStore} 注入点
 * （例如 {@code EmergencyRagController}）会自动指向公共知识域 —— 语义上正好正确：
 * 没走数据域判定的老接口，只应该看得到公开知识。
 */
@Configuration
public class VectorStoreConfiguration {

    /** 公共知识域索引名。 */
    public static final String PUBLIC_INDEX = "emergency_public_index";
    /** 公共知识域 key 前缀。 */
    public static final String PUBLIC_PREFIX = "embedding:public:";
    /** 患者隐私域索引名。 */
    public static final String PATIENT_INDEX = "emergency_patient_index";
    /** 患者隐私域 key 前缀（与公共域不存在包含关系，避免前缀删除时互相误伤）。 */
    public static final String PATIENT_PREFIX = "embedding:patient:";

    /** 元数据字段名：数据域。 */
    public static final String META_DOMAIN = "domain";
    /** 元数据字段名：机构/院区。 */
    public static final String META_TENANT = "tenantId";
    /** 元数据字段名：患者标识。 */
    public static final String META_PATIENT = "patientId";
    /** 元数据字段名：分类。 */
    public static final String META_CATEGORY = "category";
    /** 元数据字段名：标题。未在 schema 里声明的话，检索结果里读不到它。 */
    public static final String META_TITLE = "title";
    /** 元数据字段名：来源。 */
    public static final String META_SOURCE = "source";

    @Bean
    @Primary
    public RedisVectorStore publicVectorStore(EmbeddingModel embeddingModel,
                                             JedisConnectionFactory connectionFactory,
                                             ObjectProvider<BatchingStrategy> batchingStrategy) {
        return build(embeddingModel, connectionFactory, batchingStrategy,
                PUBLIC_INDEX, PUBLIC_PREFIX,
                List.of(RedisVectorStore.MetadataField.tag(META_DOMAIN),
                        RedisVectorStore.MetadataField.tag(META_CATEGORY),
                        // title/source 用 TEXT：它们是给人看的内容，不需要精确匹配，
                        // 而且标题里有空格和中文，用 TAG 会踩 RediSearch 的分隔符转义问题
                        RedisVectorStore.MetadataField.text(META_TITLE),
                        RedisVectorStore.MetadataField.text(META_SOURCE)));
    }

    @Bean
    public RedisVectorStore patientVectorStore(EmbeddingModel embeddingModel,
                                              JedisConnectionFactory connectionFactory,
                                              ObjectProvider<BatchingStrategy> batchingStrategy) {
        return build(embeddingModel, connectionFactory, batchingStrategy,
                PATIENT_INDEX, PATIENT_PREFIX,
                List.of(RedisVectorStore.MetadataField.tag(META_DOMAIN),
                        RedisVectorStore.MetadataField.tag(META_TENANT),
                        RedisVectorStore.MetadataField.tag(META_PATIENT),
                        RedisVectorStore.MetadataField.tag(META_CATEGORY),
                        RedisVectorStore.MetadataField.text(META_TITLE),
                        RedisVectorStore.MetadataField.text(META_SOURCE)));
    }

    /**
     * 按域构建向量库。{@code initializeSchema=true} 让 Spring AI 在启动时自动建索引，
     * 索引结构由这里声明的 metadataFields 决定 —— 这正是自动配置缺的那一块。
     */
    private RedisVectorStore build(EmbeddingModel embeddingModel,
                                   JedisConnectionFactory connectionFactory,
                                   ObjectProvider<BatchingStrategy> batchingStrategy,
                                   String indexName, String prefix,
                                   List<RedisVectorStore.MetadataField> metadataFields) {
        RedisVectorStore.Builder builder = RedisVectorStore.builder(jedisPooled(connectionFactory), embeddingModel)
                .initializeSchema(true)
                .indexName(indexName)
                .prefix(prefix)
                .metadataFields(metadataFields);
        // 与自动配置保持一致：容器里有批处理策略就用它，没有则用 Spring AI 的默认策略
        BatchingStrategy strategy = batchingStrategy.getIfAvailable();
        if (strategy != null) {
            builder = builder.batchingStrategy(strategy);
        }
        return builder.build();
    }

    /**
     * 从 Spring Data 的 JedisConnectionFactory 造一个 JedisPooled 给向量库用。
     *
     * <p>逻辑与 M7 自动配置里的私有方法一致，照抄过来是为了让向量库与
     * {@code spring.data.redis.*} 保持同一份连接参数（host/port/密码/超时/SSL），
     * 而不是另外读一套配置 —— 两套配置必然会产生"哪天改了一处忘了另一处"的问题。
     */
    private JedisPooled jedisPooled(JedisConnectionFactory factory) {
        DefaultJedisClientConfig config = DefaultJedisClientConfig.builder()
                .ssl(factory.isUseSsl())
                .clientName(factory.getClientName())
                .timeoutMillis(factory.getTimeout())
                .password(factory.getPassword())
                .build();
        return new JedisPooled(new HostAndPort(factory.getHostName(), factory.getPort()), config);
    }

    /** 便于测试断言：公共域 Bean 名。 */
    public static final String BEAN_PUBLIC = "publicVectorStore";
    /** 患者域 Bean 名。 */
    public static final String BEAN_PATIENT = "patientVectorStore";

    /** 类型安全的取用方式（供 VectorStore 之外的地方按域取）。 */
    public static VectorStore pick(KnowledgeDomain domain, VectorStore publicStore, VectorStore patientStore) {
        return domain == KnowledgeDomain.PATIENT ? patientStore : publicStore;
    }
}
