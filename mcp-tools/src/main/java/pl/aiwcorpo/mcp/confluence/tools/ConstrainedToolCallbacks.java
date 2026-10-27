package pl.aiwcorpo.mcp.confluence.tools;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.aop.support.AopUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code MethodToolCallbackProvider} with one addition: the inputSchema of each tool carries the limits of
 * its {@link StringArgument} parameters ({@code minLength}, {@code maxLength}, {@code pattern}) and always
 * {@code additionalProperties: false}. The callbacks stay {@link MethodToolCallback}s, so the starter still
 * reads {@code @ToolRisk} from the method.
 */
public final class ConstrainedToolCallbacks {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ConstrainedToolCallbacks() {
    }

    public static ToolCallback[] of(Object... toolObjects) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            Class<?> type = AopUtils.getTargetClass(toolObject);
            Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Tool.class))
                    .sorted(Comparator.comparing(Method::getName))
                    .forEach(method -> callbacks.add(callback(toolObject, method)));
        }
        return callbacks.toArray(ToolCallback[]::new);
    }

    private static ToolCallback callback(Object toolObject, Method method) {
        ToolDefinition generated = ToolDefinitions.from(method);
        ToolDefinition constrained = ToolDefinition.builder()
                .name(generated.name())
                .description(generated.description())
                .inputSchema(constrain(generated.inputSchema(), method))
                .build();
        return MethodToolCallback.builder()
                .toolDefinition(constrained)
                .toolMetadata(ToolMetadata.from(method))
                .toolMethod(method)
                .toolObject(toolObject)
                .toolCallResultConverter(ToolUtils.getToolCallResultConverter(method))
                .build();
    }

    static String constrain(String inputSchema, Method method) {
        ObjectNode schema = (ObjectNode) JSON.readTree(inputSchema);
        schema.put("additionalProperties", false);
        ObjectNode properties = (ObjectNode) schema.get("properties");
        for (Parameter parameter : method.getParameters()) {
            StringArgument limits = parameter.getAnnotation(StringArgument.class);
            if (limits == null) {
                continue;
            }
            if (properties == null || !(properties.get(parameter.getName()) instanceof ObjectNode property)) {
                throw new IllegalStateException("no schema property for " + method.getName() + "("
                        + parameter.getName() + "); compile with -parameters");
            }
            property.put("minLength", limits.minLength());
            property.put("maxLength", limits.maxLength());
            if (!limits.pattern().isEmpty()) {
                property.put("pattern", limits.pattern());
            }
        }
        return JSON.writeValueAsString(schema);
    }
}
