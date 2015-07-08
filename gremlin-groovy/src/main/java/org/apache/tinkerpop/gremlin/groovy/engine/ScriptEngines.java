/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.groovy.engine;

import org.apache.tinkerpop.gremlin.groovy.CompilerCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.DefaultImportCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.SecurityCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.ThreadInterruptCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.TimedInterruptCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.jsr223.DependencyManager;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngineFactory;
import org.apache.tinkerpop.gremlin.groovy.plugin.GremlinPlugin;
import org.apache.tinkerpop.gremlin.groovy.plugin.IllegalEnvironmentException;
import org.kohsuke.groovy.sandbox.GroovyInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Holds a batch of the configured {@code ScriptEngine} objects for the server.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class ScriptEngines implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ScriptEngines.class);

    private final static ScriptEngineManager SCRIPT_ENGINE_MANAGER = new ScriptEngineManager();

    /**
     * {@code ScriptEngine} objects configured for the server keyed on the language name.
     */
    private final Map<String, ScriptEngine> scriptEngines = new ConcurrentHashMap<>();

    private final AtomicInteger evaluationCount = new AtomicInteger(0);
    private volatile boolean controlOperationExecuting = false;

    private static final GremlinGroovyScriptEngineFactory gremlinGroovyScriptEngineFactory = new GremlinGroovyScriptEngineFactory();
    private final Consumer<ScriptEngines> initializer;

    public ScriptEngines(final Consumer<ScriptEngines> initializer) {
        this.initializer = initializer;
        this.initializer.accept(this);
    }

    /**
     * Evaluate a script with {@code Bindings} for a particular language.
     */
    public Object eval(final String script, final Bindings bindings, final String language) throws ScriptException {
        if (!scriptEngines.containsKey(language))
            throw new IllegalArgumentException(String.format("Language [%s] not supported", language));

        try {
            awaitControlOp();
            evaluationCount.incrementAndGet();
            return scriptEngines.get(language).eval(script, bindings);
        } finally {
            evaluationCount.decrementAndGet();
        }
    }

    /**
     * Evaluate a script with {@code Bindings} for a particular language.
     */
    public Object eval(final Reader reader, final Bindings bindings, final String language)
            throws ScriptException {
        if (!scriptEngines.containsKey(language))
            throw new IllegalArgumentException("Language [%s] not supported");

        try {
            evaluationCount.incrementAndGet();
            return scriptEngines.get(language).eval(reader, bindings);
        } finally {
            evaluationCount.decrementAndGet();
        }
    }

    /**
     * Compiles a script without executing it.
     *
     * @throws java.lang.UnsupportedOperationException if the {@link ScriptEngine} implementation does not implement
     * the {@link javax.script.Compilable} interface.
     */
    public CompiledScript compile(final String script, final String language) throws ScriptException {
        if (!scriptEngines.containsKey(language))
            throw new IllegalArgumentException("Language [%s] not supported");

        try {
            evaluationCount.incrementAndGet();
            final ScriptEngine scriptEngine = scriptEngines.get(language);
            if (!Compilable.class.isAssignableFrom(scriptEngine.getClass()))
                throw new UnsupportedOperationException(String.format("ScriptEngine for %s does not implement %s", language, Compilable.class.getName()));

            final Compilable compilable = (Compilable) scriptEngine;
            return compilable.compile(script);
        } finally {
            evaluationCount.decrementAndGet();
        }
    }

    /**
     * Compiles a script without executing it.
     *
     * @throws java.lang.UnsupportedOperationException if the {@link ScriptEngine} implementation does not implement
     * the {@link javax.script.Compilable} interface.
     */
    public CompiledScript compile(final Reader script, final String language) throws ScriptException {
        if (!scriptEngines.containsKey(language))
            throw new IllegalArgumentException("Language [%s] not supported");

        try {
            evaluationCount.incrementAndGet();
            final ScriptEngine scriptEngine = scriptEngines.get(language);
            if (scriptEngine instanceof Compilable)
                throw new UnsupportedOperationException(String.format("ScriptEngine for %s does not implement %s", language, Compilable.class.getName()));

            final Compilable compilable = (Compilable) scriptEngine;
            return compilable.compile(script);
        } finally {
            evaluationCount.decrementAndGet();
        }
    }

    /**
     * Reload a {@code ScriptEngine} with fresh imports.  Waits for any existing script evaluations to complete but
     * then blocks other operations until complete.
     */
    public void reload(final String language, final Set<String> imports, final Set<String> staticImports,
                       final Map<String, Object> config) {
        signalControlOp();

        try {
            if (scriptEngines.containsKey(language))
                scriptEngines.remove(language);

            final ScriptEngine scriptEngine = createScriptEngine(language, imports, staticImports, config)
                    .orElseThrow(() -> new IllegalArgumentException("Language [%s] not supported"));
            scriptEngines.put(language, scriptEngine);

            logger.info("Loaded {} ScriptEngine", language);
        } finally {
            controlOperationExecuting = false;
        }
    }

    /**
     * Perform append to the existing import list for all {@code ScriptEngine} instances that implement the
     * {@link DependencyManager} interface.  Waits for any existing script evaluations to complete but
     * then blocks other operations until complete.
     */
    public void addImports(final Set<String> imports) {
        signalControlOp();

        try {
            getDependencyManagers().forEach(dm -> dm.addImports(imports));
        } finally {
            controlOperationExecuting = false;
        }
    }

    /**
     * Pull in dependencies given some Maven coordinates.  Cycle through each {@code ScriptEngine} and determine if it
     * implements {@link DependencyManager}.  For those that do call the @{link DependencyManager#use} method to fire
     * it up.  Waits for any existing script evaluations to complete but then blocks other operations until complete.
     */
    public List<GremlinPlugin> use(final String group, final String artifact, final String version) {
        signalControlOp();

        final List<GremlinPlugin> pluginsToLoad = new ArrayList<>();
        try {
            getDependencyManagers().forEach(dm -> {
                try {
                    pluginsToLoad.addAll(dm.use(group, artifact, version));
                } catch (Exception ex) {
                    logger.warn("Could not get dependency for [{}, {}, {}] - {}", group, artifact, version, ex.getMessage());
                }
            });
        } finally {
            controlOperationExecuting = false;
        }

        return pluginsToLoad;
    }

    public void loadPlugins(final List<GremlinPlugin> plugins) {
        signalControlOp();

        getDependencyManagers().forEach(dm -> {
            try {
                dm.loadPlugins(plugins);
            } catch (IllegalEnvironmentException iee) {
                logger.warn("Some plugins may not have been loaded to {} - {}", dm.getClass().getSimpleName(), iee.getMessage());
            } catch (Exception ex) {
                logger.error(String.format("Some plugins may not have been loaded to %s", dm.getClass().getSimpleName()), ex);
            } finally {
                controlOperationExecuting = false;
            }
        });
    }

    @Override
    public void close() throws Exception {
        signalControlOp();

        try {
            scriptEngines.values().stream()
                    .filter(se -> se instanceof Closeable)
                    .map(se -> (Closeable) se).forEach(c -> {
                try {
                    c.close();
                } catch (IOException ignored) {
                }
            });
            scriptEngines.clear();
        } finally {
            controlOperationExecuting = false;
        }
    }

    /**
     * Resets the ScriptEngines and re-initializes them.  Waits for any existing script evaluations to complete but
     * then blocks other operations until complete.
     */
    public void reset() {
        signalControlOp();

        try {
            getDependencyManagers().forEach(DependencyManager::reset);
        } finally {
            controlOperationExecuting = false;
            this.initializer.accept(this);
        }
    }

    /**
     * List dependencies for those {@code ScriptEngine} objects that implement the {@link DependencyManager} interface.
     */
    public Map<String, List<Map>> dependencies() {
        final Map<String, List<Map>> m = new HashMap<>();
        scriptEngines.entrySet().stream()
                .filter(kv -> kv.getValue() instanceof DependencyManager)
                .forEach(kv -> m.put(kv.getKey(), Arrays.asList(((DependencyManager) kv.getValue()).dependencies())));
        return m;
    }

    public Map<String, List<Map>> imports() {
        final Map<String, List<Map>> m = new HashMap<>();
        scriptEngines.entrySet().stream()
                .filter(kv -> kv.getValue() instanceof DependencyManager)
                .forEach(kv -> m.put(kv.getKey(), Arrays.asList(((DependencyManager) kv.getValue()).imports())));
        return m;
    }

    /**
     * Get the set of {@code ScriptEngine} that implement {@link DependencyManager} interface.
     */
    private Set<DependencyManager> getDependencyManagers() {
        return scriptEngines.entrySet().stream()
                .map(Map.Entry::getValue)
                .filter(se -> se instanceof DependencyManager)
                .map(se -> (DependencyManager) se)
                .collect(Collectors.<DependencyManager>toSet());
    }

    private void signalControlOp() {
        awaitControlOp();
        controlOperationExecuting = true;
        awaitEvalOp();
    }

    private void awaitControlOp() {
        while (controlOperationExecuting) {
            try {
                Thread.sleep(5);
            } catch (Exception ignored) {

            }
        }
    }

    private void awaitEvalOp() {
        while (evaluationCount.get() > 0) {
            try {
                Thread.sleep(5);
            } catch (Exception ignored) {

            }
        }
    }

    private static synchronized Optional<ScriptEngine> createScriptEngine(final String language,
                                                                          final Set<String> imports,
                                                                          final Set<String> staticImports,
                                                                          final Map<String, Object> config) {
        // gremlin-groovy gets special initialization for mapper imports and such.  could implement this more
        // generically with the DependencyManager interface, but going to wait to see how other ScriptEngines
        // develop for TinkerPop3 before committing too deeply here to any specific way of doing this.
        if (language.equals(gremlinGroovyScriptEngineFactory.getLanguageName())) {
            final List<CompilerCustomizerProvider> providers = new ArrayList<>();
            providers.add(new DefaultImportCustomizerProvider(imports, staticImports));

            final String clazz = (String) config.getOrDefault("sandbox", "");
            if (!clazz.isEmpty()) {
                try {
                    final Class providerClass = Class.forName(clazz);
                    final GroovyInterceptor interceptor = (GroovyInterceptor) providerClass.newInstance();
                    providers.add(new SecurityCustomizerProvider(interceptor));
                } catch (Exception ex) {
                    logger.warn("Could not instantiate GroovyInterceptor implementation [{}] for the SecurityCustomizerProvider.  It will not be applied.", clazz);
                }
            }

            // the key to the config of the compilerCustomizerProvider is the fully qualified classname of a
            // CompilerCustomizerProvider.  the value is a list of arguments to pass to an available constructor.
            // the arguments must match in terms of type, so given that configuration typically comes from yaml
            // or properties file, it is best to stick to primitive values when possible here for simplicity.
            final Map<String,Object> compilerCustomizerProviders = (Map<String,Object>) config.getOrDefault(
                    "compilerCustomizerProviders", Collections.emptyMap());
            compilerCustomizerProviders.forEach((k,v) -> {
                try {
                    final Class providerClass = Class.forName(k);
                    if (v != null && v instanceof List && ((List) v).size() > 0) {
                        final List<Object> l = (List) v;
                        final Object[] args = new Object[l.size()];
                        l.toArray(args);

                        final Class<?>[] argClasses = new Class<?>[args.length];
                        Stream.of(args).map(a -> a.getClass()).collect(Collectors.toList()).toArray(argClasses);
                        final Constructor constructor = providerClass.getConstructor(argClasses);
                        providers.add((CompilerCustomizerProvider) constructor.newInstance(args));
                    } else {
                        providers.add((CompilerCustomizerProvider) providerClass.newInstance());
                    }
                } catch(Exception ex) {
                    logger.warn("Could not instantiate CompilerCustomizerProvider implementation [{}].  It will not be applied.", clazz);
                }
            });

            final CompilerCustomizerProvider[] providerArray = new CompilerCustomizerProvider[providers.size()];
            return Optional.of((ScriptEngine) new GremlinGroovyScriptEngine(providers.toArray(providerArray)));
        } else {
            return Optional.ofNullable(SCRIPT_ENGINE_MANAGER.getEngineByName(language));
        }
    }

}
