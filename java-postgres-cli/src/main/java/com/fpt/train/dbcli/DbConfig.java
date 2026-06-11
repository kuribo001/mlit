package com.fpt.train.dbcli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record DbConfig(String url, String user, String password) {

    public static DbConfig fromEnv() {
        Map<String, String> merged = new HashMap<>(loadDotEnv());
        merged.putAll(System.getenv());
        return from(merged);
    }

    static DbConfig from(Map<String, String> env) {
        String url = firstNonBlank(env.get("DB_URL"));
        String host = firstNonBlank(env.get("DB_HOST"), env.get("PGHOST"));
        String port = firstNonBlank(env.get("DB_PORT"), env.get("PGPORT"), "5432");
        String database = firstNonBlank(env.get("DB_NAME"), env.get("PGDATABASE"));
        String user = firstNonBlank(env.get("DB_USER"), env.get("PGUSER"));
        String password = firstNonNull(env.get("DB_PASSWORD"), env.get("PGPASSWORD"), "");
        String params = firstNonBlank(env.get("DB_PARAMS"));

        if (url == null && host != null && database != null) {
            StringBuilder builder = new StringBuilder("jdbc:postgresql://")
                .append(host)
                .append(':')
                .append(port)
                .append('/')
                .append(database);

            if (params != null) {
                builder.append('?').append(params);
            }

            url = builder.toString();
        }

        List<String> missing = new ArrayList<>();
        if (url == null) {
            missing.add("DB_URL hoặc DB_HOST + DB_NAME");
        }
        if (user == null) {
            missing.add("DB_USER");
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Thiếu cấu hình database: " + String.join(", ", missing));
        }

        return new DbConfig(url, user, password);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, String> loadDotEnv() {
        Path envFile = Path.of(".env");
        Map<String, String> values = new HashMap<>();

        if (!Files.exists(envFile)) {
            return values;
        }

        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int separatorIndex = trimmed.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, separatorIndex).trim();
                String value = trimmed.substring(separatorIndex + 1).trim();
                values.put(key, stripQuotes(value));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được file .env: " + e.getMessage(), e);
        }

        return values;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
