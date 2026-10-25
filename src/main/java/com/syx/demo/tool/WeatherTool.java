package com.syx.demo.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class WeatherTool {

    @Tool(description = "查询指定城市的天气信息")
    public WeatherResult getWeather(@ToolParam(description = "城市名称") String city) {
        // 这里调用真实的天气 API
        // 例如：调用 和风天气、OpenWeatherMap 等
        return new WeatherResult(city, "25°C", "晴朗", "东北风 3级");
    }

    public record WeatherResult(String city, String temperature, String condition, String wind) {}
}