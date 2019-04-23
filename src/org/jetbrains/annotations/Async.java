package org.jetbrains.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface Async {
   @Retention(RetentionPolicy.CLASS)
   @Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PARAMETER})
   public @interface Execute {
   }

   @Retention(RetentionPolicy.CLASS)
   @Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PARAMETER})
   public @interface Schedule {
   }
}
