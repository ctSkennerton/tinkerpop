package com.tinkerpop.gremlin.groovy.jsr223;

import com.tinkerpop.gremlin.process.Traversal;
import com.tinkerpop.gremlin.structure.Compare;
import com.tinkerpop.gremlin.structure.Contains;
import com.tinkerpop.gremlin.structure.Direction;
import com.tinkerpop.gremlin.structure.Graph;
import com.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import groovy.grape.Grape;
import groovy.json.JsonBuilder;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.javatuples.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public abstract class AbstractImportCustomizerProvider implements ImportCustomizerProvider {
    protected static final String DOT_STAR = ".*";
    protected static final String EMPTY_STRING = "";
    protected static final String PERIOD = ".";

    protected final Set<String> extraImports = new HashSet<>();
    protected final Set<String> extraStaticImports = new HashSet<>();

    private static final Set<String> imports = new HashSet<>();
    private static final Set<String> staticImports = new HashSet<>();

    static {
        imports.add(Graph.class.getPackage().getName() + DOT_STAR);
        imports.add(Traversal.class.getPackage().getName() + DOT_STAR);
        imports.add(TinkerGraph.class.getPackage().getName() + DOT_STAR);
        imports.add(Grape.class.getCanonicalName());
        imports.add(JsonBuilder.class.getPackage().getName() + DOT_STAR);

        staticImports.add(Direction.class.getCanonicalName() + DOT_STAR);
        staticImports.add(Compare.class.getCanonicalName() + DOT_STAR);
    }

    @Override
    public ImportCustomizer getImportCustomizer() {
        final ImportCustomizer ic = new ImportCustomizer();

        processImports(ic, imports);
        processStaticImports(ic, staticImports);
        processImports(ic, extraImports);
        processStaticImports(ic, extraStaticImports);

        return ic;
    }

    public Set<String> getImports() {
        return imports;
    }

    public Set<String> getStaticImports() {
        return staticImports;
    }

    public Set<String> getExtraImports() {
        return extraImports;
    }

    public Set<String> getExtraStaticImports() {
        return extraStaticImports;
    }

    public Set<String> getAllImports() {
        final Set<String> allImports = new HashSet<>();
        allImports.addAll(imports);
        allImports.addAll(staticImports);
        allImports.addAll(extraImports);
        allImports.addAll(extraStaticImports);

        return allImports;
    }

    private static void processStaticImports(final ImportCustomizer ic, final Set<String> staticImports) {
        for (final String staticImport : staticImports) {
            if (staticImport.endsWith(DOT_STAR)) {
                ic.addStaticStars(staticImport.replace(DOT_STAR, EMPTY_STRING));
            } else {
                final int place = staticImport.lastIndexOf(PERIOD);
                ic.addStaticImport(staticImport.substring(0, place), staticImport.substring(place + 1));
            }
        }
    }

    private static void processImports(final ImportCustomizer ic, final Set<String> imports) {
        for (final String imp : imports) {
            if (imp.endsWith(DOT_STAR)) {
                ic.addStarImports(imp.replace(DOT_STAR, EMPTY_STRING));
            } else {
                ic.addImports(imp);
            }
        }
    }
}