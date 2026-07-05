package com.w4nya.hamreporter.server.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class DataPathResolver {
    public enum OS {
        WINDOWS, MAC, LINUX, UNKNOWN
    }

    private static final OS CURRENT_OS = determineOS();

    private DataPathResolver() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static OS getOS() {
        return CURRENT_OS;
    }

    private static OS determineOS() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return OS.WINDOWS;
        } else if (osName.contains("mac")) {
            return OS.MAC;
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            return OS.LINUX;
        }
        return OS.UNKNOWN;
    }

    private static String getUserHome() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("HOME");
        }
        if (home == null || home.isBlank()) {
            home = "/";
        }
        return home;
    }

    public static Path getConfigPath(String appName) {
        String userHome = getUserHome();

        switch (CURRENT_OS) {
            case WINDOWS:
                String appData = System.getenv("APPDATA");
                if (appData != null && !appData.isBlank()) {
                    return Paths.get(appData, appName);
                }
                return Paths.get(userHome, "AppData", "Roaming", appName);

            case MAC:
                return Paths.get(userHome, "Library", "Application Support", appName);

            case LINUX:
                String xdgConfig = System.getenv("XDG_CONFIG_HOME");
                if (xdgConfig != null && !xdgConfig.isBlank()) {
                    return Paths.get(xdgConfig, appName);
                }
                return Paths.get(userHome, ".config", appName);

            default:
                return Paths.get(userHome, "." + appName, "config");
        }
    }

    public static Path getDataPath(String appName) {
        String userHome = getUserHome();

        switch (CURRENT_OS) {
            case WINDOWS:
                String localAppData = System.getenv("LOCALAPPDATA");
                if (localAppData != null && !localAppData.isBlank()) {
                    return Paths.get(localAppData, appName);
                }
                return Paths.get(userHome, "AppData", "Local", appName);

            case MAC:
                return Paths.get(userHome, "Library", "Application Support", appName);

            case LINUX:
                String xdgData = System.getenv("XDG_DATA_HOME");
                if (xdgData != null && !xdgData.isBlank()) {
                    return Paths.get(xdgData, appName);
                }
                return Paths.get(userHome, ".local", "share", appName);

            default:
                return Paths.get(userHome, "." + appName, "data");
        }
    }
}
