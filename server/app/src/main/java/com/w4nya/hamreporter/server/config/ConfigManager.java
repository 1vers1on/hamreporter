package com.w4nya.hamreporter.server.config;

import java.nio.file.Path;

import tools.jackson.dataformat.toml.TomlMapper;

public class ConfigManager {
    private static final TomlMapper tomlMapper = new TomlMapper();

    private ConfigManager() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static NodeConfig loadConfig(Path configPath) throws Exception {
        return tomlMapper.readValue(configPath.toFile(), NodeConfig.class);
    }

    public static void saveConfig(NodeConfig config, Path configPath) throws Exception {
        tomlMapper.writeValue(configPath.toFile(), config);
    }
}
