package com.tinkerpop.gremlin.structure.util;

import com.tinkerpop.gremlin.process.T;
import com.tinkerpop.gremlin.structure.Contains;
import com.tinkerpop.gremlin.structure.Element;
import com.tinkerpop.gremlin.structure.MetaProperty;
import com.tinkerpop.gremlin.structure.Property;
import com.tinkerpop.gremlin.structure.Vertex;
import com.tinkerpop.gremlin.util.function.SBiPredicate;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class HasContainer implements Serializable {

    public String key;
    public SBiPredicate predicate;
    public Object value;

    public HasContainer(final String key, final SBiPredicate predicate, final Object value) {
        this.key = key;
        this.predicate = predicate;
        this.value = value;
        if (null == this.value && !(this.predicate instanceof Contains)) {
            throw new IllegalArgumentException("For determining the existence of a property, use the Contains predicate");
        }
    }

    public HasContainer(final String key, final Contains contains) {
        this(key, contains, null);
    }

    public HasContainer(final T accessor, final SBiPredicate predicate, final Object value) {
        this(accessor.getAccessor(), predicate, value);
    }

    public HasContainer(final T accessor, final Contains contains) {
        this(accessor.getAccessor(), contains, null);
    }

    public boolean test(final Element element) {
        if (null != this.value) {

            if (this.key.equals(T.id.getAccessor()))
                return this.predicate.test(element.id(), this.value);
            else if (this.key.equals(T.label.getAccessor()))
                return this.predicate.test(element.label(), this.value);
            else if (element instanceof MetaProperty && this.key.equals(T.value.getAccessor()))
                return this.predicate.test(((MetaProperty) element).value(), this.value);
            else if (element instanceof MetaProperty && this.key.equals(T.key.getAccessor()))
                return this.predicate.test(((MetaProperty) element).key(), this.value);
            else {
                if (element instanceof Vertex) {
                    final Iterator<? extends Property> itty = element.iterators().properties(this.key);
                    while (itty.hasNext()) {
                        if (this.predicate.test(itty.next().value(), this.value))
                            return true;
                    }
                    return false;
                } else {
                    final Property property = element.property(this.key);
                    return property.isPresent() && this.predicate.test(property.value(), this.value);
                }
            }
        } else {
            return Contains.IN.equals(this.predicate) ?
                    element.property(this.key).isPresent() :
                    !element.property(this.key).isPresent();
        }
    }

    public static boolean testAll(final Element element, final List<HasContainer> hasContainers) {
        if (hasContainers.size() == 0)
            return true;
        else {
            for (final HasContainer hasContainer : hasContainers) {
                if (!hasContainer.test(element))
                    return false;
            }
            return true;
        }
    }

    public String toString() {
        return this.value == null ?
                (this.predicate == Contains.IN ?
                        "[" + this.key + "]" :
                        "[!" + this.key + "]") :
                "[" + this.key + "," + this.predicate + "," + this.value + "]";
    }
}