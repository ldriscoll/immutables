/*
    Copyright 2014 Immutables Authors and Contributors

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.immutables.generator;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.CharSink;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import org.immutables.generator.Templates.Invokable;
import org.immutables.generator.Templates.Invokation;
import static com.google.common.base.Preconditions.*;

public final class Output {
  public final Templates.Invokable error = new Templates.Invokable() {
    @Override
    @Nullable
    public Invokable invoke(Invokation invokation, Object... parameters) {
      String message = CharMatcher.WHITESPACE.trimFrom(parameters[0].toString());
      StaticEnvironment.processing().getMessager().printMessage(Diagnostic.Kind.ERROR, message);
      return null;
    }

    @Override
    public int arity() {
      return 1;
    }
  };

  public final Templates.Invokable system = new Templates.Invokable() {
    @Override
    @Nullable
    public Invokable invoke(Invokation invokation, Object... parameters) {
      String message = CharMatcher.WHITESPACE.trimFrom(parameters[0].toString());
      System.out.println(message);
      return null;
    }

    @Override
    public int arity() {
      return 1;
    }
  };

  private static abstract class OutputFilter extends Templates.Fragment {
    public OutputFilter() {
      super(1);
    }

    abstract void apply(Invokation invokation, CharSequence content, @Nullable Templates.Invokable original);

    @Override
    public final void run(Invokation invokation) {
      Object param = invokation.param(0);
      Invokable original = param instanceof Templates.Invokable
          ? (Templates.Invokable) param
          : null;

      apply(invokation, toCharSequence(param), original);
    }

    private CharSequence toCharSequence(Object param) {
      checkNotNull(param);
      // Is it worthwhile optimization?
      if (param instanceof Templates.Fragment) {
        return ((Templates.Fragment) param).toCharSequence();
      }
      return param.toString();
    }
  }

  public final Templates.Invokable trim = new OutputFilter() {
    @Override
    void apply(Invokation invokation, CharSequence content, @Nullable Templates.Invokable original) {
      invokation.out(CharMatcher.WHITESPACE.trimFrom(content));
    }
  };

  public final Templates.Invokable linesShortable = new OutputFilter() {
    private static final int LIMIT = 100;

    @Override
    void apply(Invokation invokation, CharSequence content, @Nullable Templates.Invokable original) {
      String collapsed = CharMatcher.WHITESPACE.trimAndCollapseFrom(content, ' ');
      int estimatedLimitOnThisLine = LIMIT - invokation.getCurrentIndentation().length();

      if (collapsed.length() < estimatedLimitOnThisLine) {
        invokation.out(collapsed);
      } else {
        if (original != null) {
          original.invoke(invokation);
        } else {
          invokation.out(content);
        }
      }
    }
  };

  public final Templates.Invokable collapsible = new OutputFilter() {
    @Override
    void apply(Invokation invokation, CharSequence content, @Nullable Templates.Invokable original) {
      boolean hasNonWhitespace = !CharMatcher.WHITESPACE.matchesAllOf(content);
      if (hasNonWhitespace) {
        if (original != null) {
          original.invoke(invokation);
        } else {
          invokation.out(content);
        }
      }
    }
  };

  public final Templates.Invokable java = new Templates.Invokable() {
    @Override
    public Invokable invoke(Invokation invokation, Object... parameters) {
      String packageName = parameters[0].toString();
      String simpleName = parameters[1].toString();
      Invokable body = (Invokable) parameters[2];

      ResourceKey key = new ResourceKey(packageName, simpleName);
      SourceFile javaFile = getFiles().sourceFiles.get(key);
      body.invoke(new Invokation(javaFile.consumer));
      javaFile.complete();
      return null;
    }

    @Override
    public int arity() {
      return 3;
    }
  };

  public final Templates.Invokable service = new Templates.Invokable() {
    private static final String META_INF_SERVICES = "META-INF/services/";

    @Override
    public Invokable invoke(Invokation invokation, Object... parameters) {
      String interfaceName = parameters[0].toString();
      Invokable body = (Invokable) parameters[1];

      ResourceKey key = new ResourceKey("", META_INF_SERVICES + interfaceName);
      AppendServiceFile servicesFile = getFiles().appendResourceFiles.get(key);
      body.invoke(new Invokation(servicesFile.consumer));
      return null;
    }

    @Override
    public int arity() {
      return 3;
    }
  };

  private final static class ResourceKey {
    private static Joiner PACKAGE_RESOURCE_JOINER = Joiner.on('.').skipNulls();

    final String packageName;
    final String relativeName;

    ResourceKey(String packageName, String simpleName) {
      this.packageName = checkNotNull(packageName);
      this.relativeName = checkNotNull(simpleName);
    }

    @Override
    public String toString() {
      return PACKAGE_RESOURCE_JOINER.join(Strings.emptyToNull(packageName), relativeName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(packageName, relativeName);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof ResourceKey) {
        ResourceKey other = (ResourceKey) obj;
        return this.packageName.equals(other.packageName)
            || this.relativeName.equals(other.relativeName);
      }
      return false;
    }
  }

  private static class AppendServiceFile {
    private static final Pattern SERVICE_FILE_COMMENT_LINE = Pattern.compile("^\\#.*");

    final ResourceKey key;
    final Templates.CharConsumer consumer = new Templates.CharConsumer();

    AppendServiceFile(ResourceKey key) {
      this.key = key;
    }

    void complete() {
      try {
        writeFile();
      } catch (FilerException ex) {
        throw Throwables.propagate(ex);
      } catch (IOException ex) {
        throw Throwables.propagate(ex);
      }
    }

    private void writeFile() throws IOException {
      LinkedHashSet<String> services = Sets.newLinkedHashSet();
      readExistingEntriesInto(services);
      copyNewMetaservicesInto(services);
      removeBlankLinesIn(services);
      writeLinesFrom(services);
    }

    private void readExistingEntriesInto(Collection<String> services) {
      try {
        FileObject existing = getFiler().getResource(StandardLocation.CLASS_OUTPUT, key.packageName, key.relativeName);
        FluentIterable.from(CharStreams.readLines(existing.openReader(true)))
            .filter(Predicates.not(Predicates.contains(SERVICE_FILE_COMMENT_LINE)))
            .copyInto(services);
      } catch (Exception ex) {
        // unable to read existing file
      }
    }

    private void writeLinesFrom(Iterable<String> services) throws IOException {
      new CharSink() {
        @Override
        public Writer openStream() throws IOException {
          return getFiler()
              .createResource(StandardLocation.CLASS_OUTPUT, key.packageName, key.relativeName)
              .openWriter();
        }
      }.writeLines(services, "\n");
    }

    private void removeBlankLinesIn(Iterable<String> services) {
      Iterables.removeIf(services, Predicates.equalTo(""));
    }

    private void copyNewMetaservicesInto(Collection<String> services) {
      FluentIterable.from(Splitter.on("\n").split(consumer.asCharSequence()))
          .copyInto(services);
    }

  }

  private static class SourceFile {
    final ResourceKey key;
    final Templates.CharConsumer consumer = new Templates.CharConsumer();

    SourceFile(ResourceKey key) {
      this.key = key;
    }

    void complete() {
      CharSequence sourceCode = extractSourceCode();
      try {
        try (Writer writer = getFiler().createSourceFile(key.toString()).openWriter()) {
          writer.append(sourceCode);
        }
      } catch (FilerException ex) {
        if (identicalFileIsAlreadyGenerated(sourceCode)) {
          getMessager().printMessage(Kind.MANDATORY_WARNING, "Regenerated file with the same content: " + key);
        } else {
          getMessager().printMessage(Kind.ERROR, String.format(
                  "Generated source file name collission. Attempt to overwrite already generated file: %s, %s",
                  key, ex));
        }
      } catch (IOException ex) {
        throw Throwables.propagate(ex);
      }
    }

    private boolean identicalFileIsAlreadyGenerated(CharSequence sourceCode) {
      try {
        String existingContent = new CharSource() {
          final String packagePath = !key.packageName.isEmpty() ? (key.packageName.replace('.', '/') + '/') : "";
          final String filename = key.relativeName + ".java";

          @Override
          public Reader openStream() throws IOException {
            return getFiler()
                .getResource(StandardLocation.SOURCE_OUTPUT,
                    "", packagePath + filename)
                .openReader(true);
          }
        }.read();

        if (existingContent.contentEquals(sourceCode)) {
          // We are ok, for some reason the same file is already generated,
          // happens in Eclipse for example.
          return true;
        }
      } catch (Exception ignoredAttemptToGetExistingFile) {
        // we have some other problem, not an existing file
      }
      return false;
    }

    private CharSequence extractSourceCode() {
      return PostprocessingMachine.rewrite(consumer.asCharSequence());
    }
  }

  private static Filer getFiler() {
    return StaticEnvironment.processing().getFiler();
  }

  private static Messager getMessager() {
    return StaticEnvironment.processing().getMessager();
  }

  private static Files getFiles() {
    return StaticEnvironment.getInstance(Files.class, FilesSupplier.INSTANCE);
  }

  private enum FilesSupplier implements Supplier<Files> {
    INSTANCE;

    @Override
    public Files get() {
      return new Files();
    }
  }

  // Do not use guava cache to slim down minimized jar
  @NotThreadSafe
  private static abstract class Cache<K, V> {
    private final Map<K, V> map = new HashMap<>();

    protected abstract V load(K key) throws Exception;

    final V get(K key) {
      @Nullable V value = map.get(key);
      if (value == null) {
        try {
          value = load(key);
        } catch (Exception ex) {
          throw Throwables.propagate(ex);
        }
        map.put(key, value);
      }
      return value;
    }

    public Map<K, V> asMap() {
      return map;
    }
  }

  private static class Files implements StaticEnvironment.Completable {
    final Cache<ResourceKey, SourceFile> sourceFiles = new Cache<ResourceKey, SourceFile>() {
      @Override
      public SourceFile load(ResourceKey key) throws Exception {
        return new SourceFile(key);
      }
    };

    final Cache<ResourceKey, AppendServiceFile> appendResourceFiles = new Cache<ResourceKey, AppendServiceFile>() {
      @Override
      public AppendServiceFile load(ResourceKey key) throws Exception {
        return new AppendServiceFile(key);
      }
    };

    @Override
    public void complete() {
      for (AppendServiceFile file : appendResourceFiles.asMap().values()) {
        file.complete();
      }
    }
  }
}
