package com.tinkerpop.gremlin.process.graph.step.map;

import com.tinkerpop.gremlin.process.Traversal;
import com.tinkerpop.gremlin.process.graph.marker.Reversible;
import com.tinkerpop.gremlin.process.util.FastNoSuchElementException;
import com.tinkerpop.gremlin.process.util.TraversalHelper;
import com.tinkerpop.gremlin.structure.Element;
import com.tinkerpop.gremlin.structure.Property;
import com.tinkerpop.gremlin.structure.PropertyType;
import com.tinkerpop.gremlin.util.IteratorUtils;
import com.tinkerpop.gremlin.util.StreamFactory;

import java.util.Arrays;
import java.util.Iterator;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class PropertiesStep<E> extends FlatMapStep<Element, E> implements Reversible {

    protected final String[] propertyKeys;
    protected final PropertyType returnType;

    public PropertiesStep(final Traversal traversal, final PropertyType propertyType, final String... propertyKeys) {
        super(traversal);
        this.returnType = propertyType;
        this.propertyKeys = propertyKeys;
        this.setFunction(traverser -> StreamFactory.stream(traverser.get().iterators().propertyIterator(propertyKeys))
                .filter(p -> (propertyType.forHiddens() && p.isHidden()) || (!propertyType.forHiddens() && !p.isHidden()))
                .map(p -> (E) (propertyType.forValues() ? p.value() : p)).iterator());
    }

    public PropertyType getReturnType() {
        return this.returnType;
    }

    public String[] getPropertyKeys() {
        return this.propertyKeys;
    }

    @Override
    public void reverse() {
        // TODO: only works if its element->property ... how do we do dynamic reversibility?
        TraversalHelper.replaceStep(this, new PropertyElementStep(this.traversal), this.traversal);
    }

    @Override
    public String toString() {
        return this.propertyKeys.length == 0 ?
                TraversalHelper.makeStepString(this, this.returnType.name().toLowerCase()) :
                TraversalHelper.makeStepString(this, this.returnType.name().toLowerCase(), Arrays.toString(this.propertyKeys));
    }
}
