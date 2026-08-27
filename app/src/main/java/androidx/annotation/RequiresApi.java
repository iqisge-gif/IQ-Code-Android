package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE,ElementType.METHOD,ElementType.CONSTRUCTOR,ElementType.FIELD})
public @interface RequiresApi { int value() default 1; int api() default 1; }
