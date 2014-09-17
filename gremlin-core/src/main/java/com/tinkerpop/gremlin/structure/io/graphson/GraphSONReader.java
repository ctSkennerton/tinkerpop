package com.tinkerpop.gremlin.structure.io.graphson;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.tinkerpop.gremlin.process.T;
import com.tinkerpop.gremlin.structure.Direction;
import com.tinkerpop.gremlin.structure.Edge;
import com.tinkerpop.gremlin.structure.Element;
import com.tinkerpop.gremlin.structure.Graph;
import com.tinkerpop.gremlin.structure.Vertex;
import com.tinkerpop.gremlin.structure.io.GraphReader;
import com.tinkerpop.gremlin.structure.util.batch.BatchGraph;
import com.tinkerpop.gremlin.structure.util.detached.DetachedEdge;
import com.tinkerpop.gremlin.structure.util.detached.DetachedVertex;
import com.tinkerpop.gremlin.util.function.FunctionUtils;
import com.tinkerpop.gremlin.util.function.SFunction;
import org.javatuples.Pair;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A @{link GraphReader} that constructs a graph from a JSON-based representation of a graph and its elements.
 * This implementation only supports JSON data types and is therefore lossy with respect to data types (e.g. a
 * float will become a double, element IDs may not be retrieved in the format they were serialized, etc.).
 * {@link Edge} and {@link Vertex} objects are serialized to {@code Map} instances.  If an
 * {@link com.tinkerpop.gremlin.structure.Element} is used as a key, it is coerced to its identifier.  Other complex
 * objects are converted via {@link Object#toString()}.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class GraphSONReader implements GraphReader {
    private final ObjectMapper mapper;
    private final long batchSize;
    private final String vertexIdKey;
    private final String edgeIdKey;

    final TypeReference<Map<String, Object>> mapTypeReference = new TypeReference<Map<String, Object>>() {
    };

    public GraphSONReader(final ObjectMapper mapper, final long batchSize,
                          final String vertexIdKey, final String edgeIdKey) {
        this.mapper = mapper;
        this.batchSize = batchSize;
        this.vertexIdKey = vertexIdKey;
        this.edgeIdKey = edgeIdKey;
    }

    @Override
    public void readGraph(final InputStream inputStream, final Graph graphToWriteTo) throws IOException {
        final BatchGraph graph;
        try {
            // will throw an exception if not constructed properly
            graph = BatchGraph.build(graphToWriteTo)
                    .vertexIdKey(vertexIdKey)
                    .edgeIdKey(edgeIdKey)
                    .bufferSize(batchSize).create();
        } catch (Exception ex) {
            throw new IOException("Could not instantiate BatchGraph wrapper", ex);
        }

        final JsonFactory factory = mapper.getFactory();

        try (JsonParser parser = factory.createParser(inputStream)) {
            if (parser.nextToken() != JsonToken.START_OBJECT)
                throw new IOException("Expected data to start with an Object");

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                final String fieldName = parser.getCurrentName();
                parser.nextToken();

                if (fieldName.equals(GraphSONTokens.PROPERTIES)) {
                    final Map<String, Object> graphProperties = parser.readValueAs(mapTypeReference);
                    if (graphToWriteTo.features().graph().variables().supportsVariables())
                        graphProperties.entrySet().forEach(entry -> graphToWriteTo.variables().set(entry.getKey(), entry.getValue()));
                } else if (fieldName.equals(GraphSONTokens.VERTICES)) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        final Map<String, Object> vertexData = parser.readValueAs(mapTypeReference);
                        readVertexData(vertexData, detachedVertex -> {
                            final Vertex v = Optional.ofNullable(graph.v(detachedVertex.id())).orElse(
                                    graph.addVertex(T.label, detachedVertex.label(), T.id, detachedVertex.id()));
                            // todo: properties on properties
                            detachedVertex.iterators().properties().forEachRemaining(p -> v.<Object>property(p.key(), p.value()));
                            detachedVertex.iterators().hiddens().forEachRemaining(p -> v.<Object>property(Graph.Key.hide(p.key()), p.value()));
                            return v;
                        });
                    }
                } else if (fieldName.equals(GraphSONTokens.EDGES)) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        final Map<String, Object> edgeData = parser.readValueAs(mapTypeReference);
                        readEdgeData(edgeData, detachedEdge -> {
                            final Vertex vOut = graph.v(detachedEdge.iterators().vertices(Direction.OUT).next().id());
                            final Vertex vIn = graph.v(detachedEdge.iterators().vertices(Direction.IN).next().id());
                            // batchgraph checks for edge id support and uses it if possible.
                            final Edge e = vOut.addEdge(edgeData.get(GraphSONTokens.LABEL).toString(), vIn, T.id, detachedEdge.id());
                            detachedEdge.iterators().properties().forEachRemaining(p -> e.<Object>property(p.key(), p.value()));
                            detachedEdge.iterators().hiddens().forEachRemaining(p -> e.<Object>property(Graph.Key.hide(p.key()), p.value()));
                            return e;
                        });
                    }
                } else
                    throw new IllegalStateException(String.format("Unexpected token in GraphSON - %s", fieldName));
            }

            graph.tx().commit();
        } catch (Exception ex) {
            // todo: can't call rollback on BatchGraph...................
            // rollback whatever portion failed
            graph.tx().rollback();
            throw new IOException(ex);
        }
    }

    @Override
    public Iterator<Vertex> readVertices(final InputStream inputStream, final Direction direction,
                                         final SFunction<DetachedVertex, Vertex> vertexMaker,
                                         final SFunction<DetachedEdge, Edge> edgeMaker) throws IOException {
        final BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
        return br.lines().<Vertex>map(FunctionUtils.wrapFunction(line -> readVertex(new ByteArrayInputStream(line.getBytes()), direction, vertexMaker, edgeMaker))).iterator();
    }

    @Override
    public Edge readEdge(final InputStream inputStream, final SFunction<DetachedEdge, Edge> edgeMaker) throws IOException {
        final Map<String, Object> edgeData = mapper.readValue(inputStream, mapTypeReference);
        return readEdgeData(edgeData, edgeMaker);
    }

    @Override
    public Vertex readVertex(final InputStream inputStream, final SFunction<DetachedVertex, Vertex> vertexMaker) throws IOException {
        final Map<String, Object> vertexData = mapper.readValue(inputStream, mapTypeReference);
        return readVertexData(vertexData, vertexMaker);
    }

    @Override
    public Vertex readVertex(final InputStream inputStream, final Direction direction,
                             final SFunction<DetachedVertex, Vertex> vertexMaker,
                             final SFunction<DetachedEdge, Edge> edgeMaker) throws IOException {
        final Map<String, Object> vertexData = mapper.readValue(inputStream, mapTypeReference);
        final Vertex v = readVertexData(vertexData, vertexMaker);

        if (vertexData.containsKey(GraphSONTokens.OUT_E) && (direction == Direction.BOTH || direction == Direction.OUT))
            readVertexEdges(edgeMaker, vertexData, GraphSONTokens.OUT_E);

        if (vertexData.containsKey(GraphSONTokens.IN_E) && (direction == Direction.BOTH || direction == Direction.IN))
            readVertexEdges(edgeMaker, vertexData, GraphSONTokens.IN_E);

        return v;
    }

    private static void readVertexEdges(final SFunction<DetachedEdge, Edge> edgeMaker, final Map<String, Object> vertexData, final String direction) throws IOException {
        final List<Map<String, Object>> edgeDatas = (List<Map<String, Object>>) vertexData.get(direction);
        for (Map<String, Object> edgeData : edgeDatas) {
            readEdgeData(edgeData, edgeMaker);
        }
    }

    private static Edge readEdgeData(final Map<String, Object> edgeData, final SFunction<DetachedEdge, Edge> edgeMaker) throws IOException {
        final Map<String, Object> properties = (Map<String, Object>) edgeData.get(GraphSONTokens.PROPERTIES);
        final Map<String, Object> hiddens = ((Map<String, Object>) edgeData.get(GraphSONTokens.HIDDENS)).entrySet().stream().collect(Collectors.toMap((Map.Entry kv) -> Graph.Key.hide(kv.getKey().toString()), (Map.Entry kv) -> kv.getValue()));

        final DetachedEdge edge = new DetachedEdge(edgeData.get(GraphSONTokens.ID),
                edgeData.get(GraphSONTokens.LABEL).toString(),
                properties, hiddens,
                Pair.with(edgeData.get(GraphSONTokens.OUT), edgeData.get(GraphSONTokens.OUT_LABEL).toString()),
                Pair.with(edgeData.get(GraphSONTokens.IN), edgeData.get(GraphSONTokens.IN_LABEL).toString()));

        return edgeMaker.apply(edge);
    }

    private static Vertex readVertexData(final Map<String, Object> vertexData, final SFunction<DetachedVertex, Vertex> vertexMaker) throws IOException {
        final Map<String, Object> metaProperties = (Map<String, Object>) vertexData.get(GraphSONTokens.PROPERTIES);
        final Map<String, Object> hiddensMetaProperties = ((Map<String, Object>) vertexData.get(GraphSONTokens.HIDDENS)).entrySet().stream().collect(Collectors.toMap((Map.Entry kv) -> Graph.Key.hide(kv.getKey().toString()), (Map.Entry kv) -> kv.getValue()));;

        // todo: properties on properties
        final DetachedVertex vertex = new DetachedVertex(vertexData.get(GraphSONTokens.ID),
                vertexData.get(GraphSONTokens.LABEL).toString(),
                metaProperties, hiddensMetaProperties);

        return vertexMaker.apply(vertex);
    }

    public static Builder build() {
        return new Builder();
    }

    public static class Builder {
        private boolean loadCustomModules = false;
        private SimpleModule custom = null;
        private long batchSize = BatchGraph.DEFAULT_BUFFER_SIZE;
        private boolean embedTypes = false;

        // todo: uh - does this work?
        private String vertexIdKey = T.id.getAccessor();
        private String edgeIdKey = T.id.getAccessor();

        private Builder() {
        }

        public Builder vertexIdKey(final String vertexIdKey) {
            this.vertexIdKey = vertexIdKey;
            return this;
        }

        public Builder edgeIdKey(final String edgeIdKey) {
            this.edgeIdKey = edgeIdKey;
            return this;
        }

        /**
         * Supply a custom module for serialization/deserialization.
         */
        public Builder customModule(final SimpleModule custom) {
            this.custom = custom;
            return this;
        }

        /**
         * Try to load {@code SimpleModule} instances from the current classpath.  These are loaded in addition to
         * the one supplied to the {@link #customModule(com.fasterxml.jackson.databind.module.SimpleModule)};
         */
        public Builder loadCustomModules(final boolean loadCustomModules) {
            this.loadCustomModules = loadCustomModules;
            return this;
        }

        public Builder embedTypes(final boolean embedTypes) {
            this.embedTypes = embedTypes;
            return this;
        }

        /**
         * Number of mutations to perform before a commit is executed.
         */
        public Builder batchSize(final long batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public GraphSONReader create() {
            final ObjectMapper mapper = GraphSONObjectMapper.build()
                    .customModule(custom)
                    .embedTypes(embedTypes)
                    .loadCustomModules(loadCustomModules).build();
            return new GraphSONReader(mapper, batchSize, vertexIdKey, edgeIdKey);
        }
    }
}
