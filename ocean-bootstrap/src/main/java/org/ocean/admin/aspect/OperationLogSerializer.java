package org.ocean.admin.aspect;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 参数序列化、文件摘要和敏感字段脱敏。 */
@Component
public class OperationLogSerializer {

    private static final Set<String> DEFAULT_SENSITIVE_FIELDS = Set.of(
            "password", "newPassword", "oldPassword", "token", "accessToken",
            "refreshToken", "authorization", "cookie", "secret", "accessKey");

    private final ObjectMapper objectMapper;
    private final int requestMaxLength;
    private final int responseMaxLength;

    public OperationLogSerializer(
            ObjectMapper objectMapper,
            @Value("${ocean.audit.operation-log.request-max-length:8192}") int requestMaxLength,
            @Value("${ocean.audit.operation-log.response-max-length:16384}") int responseMaxLength) {
        this.objectMapper = objectMapper;
        this.requestMaxLength = requestMaxLength;
        this.responseMaxLength = responseMaxLength;
    }

    public String serializeRequest(String[] parameterNames, Object[] args, String[] extraSensitiveFields) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if (value instanceof ServletRequest || value instanceof ServletResponse) {
                continue;
            }
            String name = parameterNames != null && i < parameterNames.length
                    ? parameterNames[i]
                    : "arg" + i;
            parameters.put(name, summarizeFiles(value));
        }
        return serialize(parameters, requestMaxLength, extraSensitiveFields);
    }

    public String serializeResponse(Object response, String[] extraSensitiveFields) {
        return serialize(summarizeFiles(response), responseMaxLength, extraSensitiveFields);
    }

    private String serialize(Object value, int maxLength, String[] extraSensitiveFields) {
        try {
            String json = objectMapper.writeValueAsString(value);
            json = redact(json, DEFAULT_SENSITIVE_FIELDS);
            if (extraSensitiveFields != null) {
                json = redact(json, Set.of(extraSensitiveFields));
            }
            return truncate(json, maxLength);
        } catch (Exception ex) {
            return "[unserializable:" + ex.getClass().getSimpleName() + "]";
        }
    }

    private Object summarizeFiles(Object value) {
        if (value instanceof MultipartFile file) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("filename", file.getOriginalFilename());
            summary.put("contentType", file.getContentType());
            summary.put("size", file.getSize());
            return summary;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> summarized = new ArrayList<>(collection.size());
            collection.forEach(item -> summarized.add(summarizeFiles(item)));
            return summarized;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> summarized = new ArrayList<>(Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) {
                summarized.add(summarizeFiles(Array.get(value, i)));
            }
            return summarized;
        }
        return value;
    }

    private String redact(String json, Set<String> fieldNames) {
        String result = json;
        for (String fieldName : fieldNames) {
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }
            Pattern pattern = Pattern.compile(
                    "(?i)(\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*)"
                            + "(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|[^,}\\]]*)");
            result = pattern.matcher(result).replaceAll("$1\\\"******\\\"");
        }
        return result;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }
}
