package com.tinkerpop.gremlin.process.computer.traversal;

import com.tinkerpop.gremlin.process.Path;
import com.tinkerpop.gremlin.process.Step;
import com.tinkerpop.gremlin.process.Traversal;
import com.tinkerpop.gremlin.process.Traverser;
import com.tinkerpop.gremlin.process.computer.MessageType;
import com.tinkerpop.gremlin.process.computer.Messenger;
import com.tinkerpop.gremlin.process.graph.marker.VertexCentric;
import com.tinkerpop.gremlin.process.util.MapHelper;
import com.tinkerpop.gremlin.process.util.SingleIterator;
import com.tinkerpop.gremlin.process.util.TraversalHelper;
import com.tinkerpop.gremlin.structure.Element;
import com.tinkerpop.gremlin.structure.Property;
import com.tinkerpop.gremlin.structure.Vertex;
import com.tinkerpop.gremlin.structure.util.detached.DetachedPath;
import com.tinkerpop.gremlin.util.function.SSupplier;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class TraversalPathMessage extends TraversalMessage {

    private TraversalPathMessage() {
    }

    private TraversalPathMessage(final Traverser.System traverser) {
        super(traverser);
        this.traverser.setPath(DetachedPath.detach(this.traverser.getPath()));
    }

    public static TraversalPathMessage of(final Traverser.System traverser) {
        return new TraversalPathMessage(traverser);
    }

    public static boolean execute(final Vertex vertex, final Messenger messenger, final SSupplier<Traversal> traversalSupplier) {

        final TraverserPathTracker tracker = vertex.value(TraversalVertexProgram.TRAVERSER_TRACKER);
        final Traversal traversal = traversalSupplier.get();
        traversal.strategies().apply();

        final AtomicBoolean voteToHalt = new AtomicBoolean(true);
        messenger.receiveMessages(MessageType.Global.of()).forEach(message -> {
            ((TraversalPathMessage) message).traverser.inflate(vertex, traversal);
            if (((TraversalPathMessage) message).executePaths(vertex, messenger, tracker, traversal))
                voteToHalt.set(false);
        });
        tracker.getPreviousObjectTracks().forEach((object, traversers) -> {
            traversers.forEach(traverser -> {
                if (traverser.isDone()) {
                    if (object instanceof Path) {
                        incrMap(tracker.getDoneObjectTracks(), DetachedPath.detach(((Path) object)), traverser);
                    } else {
                        incrMap(tracker.getDoneObjectTracks(), object, traverser);
                    }
                } else {
                    final Step step = TraversalHelper.getStep(traverser.getFuture(), traversal);
                    if (step instanceof VertexCentric) ((VertexCentric) step).setCurrentVertex(vertex);
                    step.addStarts(new SingleIterator(traverser));
                    if (processStep(step, messenger, tracker))
                        voteToHalt.set(false);
                }
            });
        });
        return voteToHalt.get();

    }

    private boolean executePaths(final Vertex vertex, final Messenger messenger,
                                 final TraverserPathTracker tracker,
                                 final Traversal traversal) {
        if (this.traverser.isDone()) {
            incrMap(tracker.getDoneGraphTracks(), this.traverser.get(), this.traverser);
            return false;
        }

        final Step step = TraversalHelper.getStep(this.traverser.getFuture(), traversal);
        if (step instanceof VertexCentric) ((VertexCentric) step).setCurrentVertex(vertex);
        step.addStarts(new SingleIterator(this.traverser));
        final boolean messagesToBeSent = processStep(step, messenger, tracker);
        incrMap(tracker.getGraphTracks(), this.traverser.get(), this.traverser);
        return messagesToBeSent;
    }

    private static boolean processStep(final Step<?, ?> step, final Messenger messenger, final TraverserPathTracker tracker) {
        final boolean messageSent = step.hasNext();
        step.forEachRemaining(traverser -> {
            final Object end = traverser.get();
            if (end instanceof Element || end instanceof Property) {
                messenger.sendMessage(
                        MessageType.Global.of(getHostingVertices(end)),
                        TraversalPathMessage.of((Traverser.System) traverser));
            } else {
                if (end instanceof Path) {
                    incrMap(tracker.getObjectTracks(), DetachedPath.detach(((Path) end)), (Traverser.System) traverser);
                } else {
                    incrMap(tracker.getObjectTracks(), end, (Traverser.System) traverser);
                }
            }
        });
        return messageSent;
    }

}
