package com.syx.demo.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动前预检：在 Spring 容器 refresh（创建 VectorStore Bean）之前，先探测 Redis。
 *
 * <p>解决两个坑：
 * <ol>
 *   <li>Redis 没启动时，Spring 抛的是 Jedis 5 的
 *       {@code Failed to connect to any host resolved for DNS name.}——这句话把"连接超时/被拒绝"
 *       全归因成"域名解析失败"，极易误判。这里提前打印中文结论。</li>
 *   <li>更隐蔽的一种情况：<b>Redis 在 WSL2 里活得好好的，但 Windows 的 localhost 端口转发
 *       (relay) 失效了</b>。此时应用一样起不来，但用户会以为 Redis 没启动而不停重启它。
 *       本类会通过 ARP 表找到 WSL 虚拟机的 IP（Hyper-V 网卡 MAC 前缀 00-15-5d），
 *       直接连过去发 Redis 的 PING 命令验证，从而把这种情况单独识别出来。</li>
 * </ol>
 *
 * <p>本检查只做提示，不阻断启动；最终仍由 Spring 自己决定是否失败。
 */
public class RedisPreflightChecker implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final String SEP = "============================================";

    /** Hyper-V 虚拟网卡的 MAC 前缀，WSL2 虚拟机的网卡就是它 */
    private static final String HYPERV_MAC_PREFIX = "00-15-5d";

    private static final Pattern ARP_LINE = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d{1,3}){3})\\s+([0-9a-fA-F]{2}(?:-[0-9a-fA-F]{2}){5})");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();

        // 与 RedisVectorStoreAutoConfiguration 保持一致：属性缺省视为 redis（matchIfMissing = true）
        String type = env.getProperty("spring.ai.vectorstore.type", "redis");
        if (!"redis".equalsIgnoreCase(type)) {
            return;
        }

        String host = env.getProperty("spring.data.redis.host", "localhost");
        int port = Integer.parseInt(env.getProperty("spring.data.redis.port", "6379"));

        System.out.println(SEP);
        System.out.println("🔎 启动前检查：Redis 向量库连通性");
        System.out.println("   目标：" + host + ":" + port);

        String failReason = probeRedis(host, port);
        if (failReason == null) {
            System.out.println("✅ Redis 连接正常（已收到 PONG），继续启动…");
            System.out.println(SEP);
            return;
        }

        System.out.println("❌ Redis 连接失败：" + host + ":" + port + "  →  " + failReason);

        // 最容易漏掉、也最致命的一种：端口本身就被 Windows 保留了，谁都绑不上。
        // 这种情况下 Redis 会在启动瞬间失败退出，而且错误被吞掉（见 printPortExcludedHelp）。
        if (checkPortState(port) == PortState.EXCLUDED) {
            printPortExcludedHelp(port);
            return;
        }

        // 关键分支：配置的 host 连不上，但 WSL 虚拟机里其实有活着的 Redis
        List<String> wslIps = findWslVmIps();
        for (String ip : wslIps) {
            if (probeRedis(ip, port) == null) {
                printRelayBrokenHelp(ip, port, host);
                return;
            }
        }

        printRedisDownHelp(port, wslIps);
    }

    /** 端口可绑定性的判定结果 */
    private enum PortState {
        /** 能绑定 → 当前没有进程在监听 */
        FREE,
        /** 绑不上但原因是"已被占用" → 有进程在监听 */
        IN_USE,
        /** 绑不上，原因是权限被拒（WSAEACCES）→ 落在 Windows 排除端口区间里 */
        EXCLUDED,
        UNKNOWN
    }

    /**
     * 试探本机能否绑定该端口。
     *
     * <p>Windows 上有「排除端口区间」(excluded port range) 这一机制：Hyper-V / WSL 的 HNS
     * 会从<b>动态端口范围</b>里划走若干区段（可用 {@code netsh int ipv4 show excludedportrange protocol=tcp}
     * 查看）。落在这些区段里的端口，<b>任何进程都绑不上</b>，bind 返回 WSAEACCES，
     * 在 Java 里表现为 {@code BindException: Permission denied}。
     *
     * <p>这对"WSL 里跑 Redis 给 Windows 用"是致命的：
     * <ul>
     *   <li>mirrored 网络模式下，WSL 共享 Windows 网络栈，Redis 的 bind 直接受该区间影响；</li>
     *   <li>NAT 模式下，WSL 的 localhost 转发层同样要在 Windows 侧绑定端口，一样会被挡住。</li>
     * </ul>
     * 结果是 Redis 启动瞬间失败退出；又因为 {@code daemonize yes} 且没配 {@code --logfile}
     * 时 redis 会把 stdout/stderr 重定向到 /dev/null，所以症状是
     * 「没有进程、没有日志、没有监听」——极难排查。
     */
    private PortState checkPortState(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress("0.0.0.0", port));
            return PortState.FREE;
        } catch (java.net.BindException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("permission") || msg.contains("access") || msg.contains("拒绝")) {
                return PortState.EXCLUDED;
            }
            if (msg.contains("in use") || msg.contains("占用") || msg.contains("已被")) {
                return PortState.IN_USE;
            }
            return PortState.UNKNOWN;
        } catch (Exception e) {
            return PortState.UNKNOWN;
        }
    }

    /** 端口被 Windows 排除区间吞掉时的提示（这是本机真实踩过的坑） */
    private void printPortExcludedHelp(int port) {
        System.out.println("");
        System.out.println("🔍 进一步检测：本机【无法绑定】" + port + " 端口（Permission denied / WSAEACCES）");
        System.out.println("   结论：这个端口落在 Windows 的【排除端口区间】里，任何进程都绑不上它——");
        System.out.println("         包括 NAT 模式下 WSL 的 localhost 转发层，也包括 mirrored 模式下");
        System.out.println("         WSL 里的 Redis 自己。所以 Redis 会在启动瞬间失败退出，");
        System.out.println("         而它的报错被吞进了 /dev/null（daemonize + 无 logfile），");
        System.out.println("         表现出来就是「没有进程、没有日志、没有监听」，换多少种启动方式都没用。");
        System.out.println("");
        System.out.println("   ① 先确认（普通权限即可）：");
        System.out.println("        netsh int ipv4 show excludedportrange protocol=tcp");
        System.out.println("      看 " + port + " 是否落在某个「开始端口~结束端口」区间内。");
        System.out.println("");
        System.out.println("   ② 根治（需要【管理员】权限的终端）：");
        System.out.println("        netsh int ipv4 set dynamic tcp start=49152 num=16384");
        System.out.println("        netsh int ipv6 set dynamic tcp start=49152 num=16384");
        System.out.println("        net stop winnat");
        System.out.println("        net start winnat");
        System.out.println("      然后重启电脑（或 wsl --shutdown），再双击桌面 start-redis.bat。");
        System.out.println("      原理：本机动态端口范围被改成了 1024~59999（默认应是 49152~65535），");
        System.out.println("            范围一放宽，HNS 划走的保留区间就顺带把 " + port + " 吃了。");
        System.out.println("");
        System.out.println("   ③ 备选（不想动系统配置）：换一个区间外的端口，例如 16379，然后同步改");
        System.out.println("      WSL 里 Redis 的启动参数和 application.yml 的 spring.data.redis.port。");
        System.out.println(SEP);
    }

    /** WSL 活着但 localhost 转发失效时的提示 */
    private void printRelayBrokenHelp(String wslIp, int port, String host) {
        System.out.println();
        System.out.println("🔍 但检测到 WSL 虚拟机里的 Redis 是【活的】：" + wslIp + ":" + port + " 回应了 PONG");
        System.out.println("   结论：WSL2 的 localhost 端口转发(relay)失效了——");
        System.out.println("         不是 Redis 的问题，也不是代码问题，重启 Redis 多少次都没用。");
        System.out.println("         所以 Windows 的 " + host + ":" + port + " 连不进去，应用因此起不来。");
        System.out.println();
        System.out.println("   ▶ 修法 A（推荐，一劳永逸）：改用 mirrored 网络模式");
        System.out.println("       在 " + System.getProperty("user.home") + "\\.wslconfig 里写：");
        System.out.println("           [wsl2]");
        System.out.println("           networkingMode=mirrored");
        System.out.println("       然后执行：  wsl --shutdown");
        System.out.println("       再重新启动 Redis（双击桌面 start-redis.bat），最后重启本应用。");
        System.out.println();
        System.out.println("   ▶ 修法 B（立刻能用，但 IP 会变）：");
        System.out.println("       把 application.yml 的 spring.data.redis.host 临时改成 " + wslIp);
        System.out.println("       注意：WSL 每次重启后 IP 可能变化，变了就要再改一次。");
        System.out.println(SEP);
    }

    /** Redis 真的没在跑时的提示 */
    private void printRedisDownHelp(int port, List<String> wslIps) {
        System.out.println("   后果：RedisVectorStore 初始化会失败，整个应用起不来。");
        System.out.println("   最常见原因：WSL2 里的 Redis Stack 没启动（WSL 重启后不会自启）。");
        if (!wslIps.isEmpty()) {
            System.out.println("   已探测到的 WSL 虚拟机 IP：" + wslIps + "（这些地址上都没有 Redis 应答）");
        }
        System.out.println();
        System.out.println("   解决办法（任选一种，在 Windows 终端 / PowerShell 里执行）：");
        System.out.println("     ① 双击桌面的 start-redis.bat");
        System.out.println("     ② wsl -d Ubuntu -- redis-stack-server --daemonize yes --bind 0.0.0.0");
        System.out.println("        （验证：wsl -d Ubuntu -- redis-cli ping  →  期望返回 PONG）");
        System.out.println();
        System.out.println("   若 redis-cli ping 已经是 PONG 但这里仍报错，说明是 localhost 端口转发问题，");
        System.out.println("   请把 " + System.getProperty("user.home") + "\\.wslconfig 配成 networkingMode=mirrored。");
        System.out.println(SEP);
    }

    /**
     * 探测目标是否为可用的 Redis。
     *
     * @return 成功返回 null；失败返回原因描述
     */
    private String probeRedis(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            socket.setSoTimeout(1500);
            // 不只是"端口能连"，还要确认对面真的是 Redis：发一个内联 PING，期望 +PONG
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buf = new byte[32];
            int n = socket.getInputStream().read(buf);
            String reply = n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII).trim() : "";
            if (reply.startsWith("+PONG")) {
                return null;
            }
            return "端口通了但对面不是 Redis（应答：" + reply + "）";
        } catch (java.net.SocketTimeoutException e) {
            return "连接超时（端口无响应，通常是服务没启动或 WSL 未运行）";
        } catch (java.net.ConnectException e) {
            return "连接被拒绝（端口无人监听）";
        } catch (java.net.UnknownHostException e) {
            return "主机名无法解析（这次真的是 DNS 问题）";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * 从 Windows 的 ARP 表里找出 WSL2 虚拟机的 IP。
     * WSL2 虚拟机的网卡是 Hyper-V 虚拟网卡，MAC 前缀固定为 00-15-5d。
     */
    private List<String> findWslVmIps() {
        List<String> ips = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("arp", "-a").redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                // 用 ISO-8859-1 解码最稳：中文系统输出是 GBK，但我们要提取的 IP/MAC 都是 ASCII，
                // 这样解码不会因乱码中断匹配
                output = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            process.waitFor(5, TimeUnit.SECONDS);

            Matcher matcher = ARP_LINE.matcher(output);
            while (matcher.find()) {
                String ip = matcher.group(1);
                String mac = matcher.group(2).toLowerCase();
                if (mac.startsWith(HYPERV_MAC_PREFIX) && !ips.contains(ip)) {
                    ips.add(ip);
                }
            }
        } catch (Exception e) {
            // ARP 读不到不影响主流程，静默降级
        }
        return ips;
    }
}
