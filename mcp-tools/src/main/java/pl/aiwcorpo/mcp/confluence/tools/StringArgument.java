package pl.aiwcorpo.mcp.confluence.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Limits of a string argument of an {@code @Tool} method. Spring AI's schema generator does not read length or
 * pattern from parameters, so {@link ConstrainedToolCallbacks} copies these into the published inputSchema.
 * The tool method enforces the same limits itself through {@link Arguments}: a client is free to ignore the
 * schema.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface StringArgument {

    int minLength() default 1;

    int maxLength();

    /** ECMA-262 compatible regular expression, anchored; empty = no pattern. */
    String pattern() default "";
}
