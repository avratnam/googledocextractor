package com.example.extractor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class EnvLoader {
    private static Map<String, String> envVars = new HashMap<>();
    private static boolean loaded = false;
    
    public static void loadEnvFile() {
        if (loaded) return;
        
        try {
            File envFile = new File(".env.local");
            if (envFile.exists()) {
                Files.lines(Paths.get(".env.local"))
                    .filter(line -> !line.trim().isEmpty() && !line.trim().startsWith("#"))
                    .forEach(line -> {
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            String key = parts[0].trim();
                            String value = parts[1].trim();
                            // Remove quotes if present
                            if (value.startsWith("\"") && value.endsWith("\"")) {
                                value = value.substring(1, value.length() - 1);
                            }
                            envVars.put(key, value);
                        }
                    });
                loaded = true;
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not read .env.local file: " + e.getMessage());
        }
    }
    
    public static String get(String key) {
        loadEnvFile();
        return envVars.getOrDefault(key, System.getenv(key));
    }
    
    public static String get(String key, String defaultValue) {
        loadEnvFile();
        return envVars.getOrDefault(key, System.getenv(key) != null ? System.getenv(key) : defaultValue);
    }
    
    public static boolean has(String key) {
        loadEnvFile();
        return envVars.containsKey(key) || System.getenv(key) != null;
    }
} 