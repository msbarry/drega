package org.bcard.signal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bcard.signal.SignalGraph;
import org.vertx.java.core.Future;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Verticle;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;

/**
 * A signal is the base construct in our functional reactive system. The signal
 * is an aggregator of values as well as producer of values. Signals use the
 * event bus as the primary means of communication and will respond on the
 * address {@code signals.[id].[channel]} where [id] is the id of the signal you
 * wish to communicate with and [channel] is the command or channel to send a
 * value to. Currently the following messages are supported:
 * 
 * <ul>
 * <li><b>.print</b> String message, causes the signal to print it's current
 * value to the logger
 * <li><b>.print.graph</b> String message, causes the signal to print it's
 * current dependency graph to the logger
 * <li><b>.increment</b> String message, causes the signal to increment it's
 * current value and send an update to all dependent signals
 * <li><b>.sendGraph</b> String message, causes this signal to reply with the
 * current {@link SignalGraph} in serialized JSON form
 * <li><b>.block</b> Boolean message, blocks the current signal from sending any
 * more value updates. Dependency graph updates are still sent. Set the message
 * to {@code true} to block this signal, {@code false} to unblock it
 * </ul>
 * 
 * @author bcard
 * 
 */
public class Signal extends Verticle {

	/* protected for testing */long value;

	private String id;

	private SignalGraph graph;

	private List<SignalGraph> discoveredDependencies;

	private final Map<SignalGraph, Long> lastValues = new HashMap<SignalGraph, Long>();

	private CombineOperator operator;

	/**
	 * Whether or not this signal is blocked from sending out value updates.
	 * {@code true} if it is blocked {@code false} if it is not blocked.
	 */
	private boolean blocked = false;

	/**
	 * The number of dependencies that this signal has. Once set this value
	 * should not change.
	 */
	private int numberOfDependencies;

	@Override
	public void start(Future<Void> startedResult) {
		JsonObject config = container.config();
		id = config.getString("id");
		container.logger().info("Starting Signal " + id);

		if (config.getField("operator") != null) {
			String name = config.getString("operator");
			operator = CombineOperator.valueOf(name);
		}

		if (config.getField("initialValue") != null) {
			value = config.getLong("initialValue");
		}

		final JsonArray dependencies = config.getArray("dependencies");
		numberOfDependencies = dependencies == null ? 0 : dependencies.size();
		discoveredDependencies = new ArrayList<SignalGraph>(
				numberOfDependencies);
		if (numberOfDependencies == 0) {
			graph = new SignalGraph(id);
			startedResult.setResult(null);
		} else {
			for (int i=0; i<dependencies.size(); i++) {
				String signal = (String)dependencies.get(i);
			
				// tell our dependencies to send us their graphs
				vertx.eventBus().send("signals."+signal+".sendGraph", "", new GraphReceiver(i, startedResult));
			}
		}

		PrintHandler printer = new PrintHandler("signals." + id + ".print");
		PrintGraphHandler printGraph = new PrintGraphHandler("signals." + id + ".print.graph");
		IncrementHandler incrementer = new IncrementHandler("signals." + id + ".increment");
		GraphHandler grapher = new GraphHandler("signals." + id + ".sendGraph");
		BlockHandler blocker = new BlockHandler("signals." + id + ".block");

		incrementer.apply(vertx.eventBus());
		printer.apply(vertx.eventBus());
		grapher.apply(vertx.eventBus());
		printGraph.apply(vertx.eventBus());
		blocker.apply(vertx.eventBus());
	}

	private class PrintHandler extends HandlerApplicator<String> {

		public PrintHandler(String address) {
			super(address);
		}

		@Override
		public void handle(Message<String> event) {
			container.logger().info(id + ": " + value);
		}
	}

	private class PrintGraphHandler extends HandlerApplicator<String> {

		public PrintGraphHandler(String address) {
			super(address);
		}

		@Override
		public void handle(Message<String> event) {
			container.logger().info(
					"Dependency Graph for " + id + ":\n" + graph);
		}
	}

	private class IncrementHandler extends HandlerApplicator<String> {

		public IncrementHandler(String address) {
			super(address);
		}

		@Override
		public void handle(Message<String> event) {
			updateValue(value + 1);
		}
	}

	private class GraphHandler extends HandlerApplicator<String> {

		public GraphHandler(String address) {
			super(address);
		}

		@Override
		public void handle(Message<String> event) {
			JsonObject obj = null;
			if (graph != null) {
				obj = new JsonObject(graph.toJson());
			}
			event.reply(obj);
		}
	}

	/**
	 * Private class that handles receiving the dependency graphs from other
	 * signals. This class saves the graphs into the
	 * {@link Signal#discoveredDependencies} array <i>in the order that they are
	 * declared in the Signal's config file, not the order they arrive</i>. When
	 * all of the dependencies have been received then the {@code finishHandler}
	 * is called.
	 * 
	 * @author bcard
	 * 
	 */
	private class GraphReceiver implements Handler<Message<JsonObject>> {

		/**
		 * The index of this dependency. This is the index to place the
		 * dependency in the {@link Signal#discoveredDependencies} list.
		 */
		private final int index;
		
		/**
		 * Handler to call when all updates have been received.
		 */
		private final Future<Void> finishHandler;
		
		/**
		 * Creates a new {@link GraphReceiver}.
		 * 
		 * @param index
		 *            index in {@link Signal#discoveredDependencies} that this
		 *            dependency corresponds to
		 * @param finishHandler
		 *            handler to call when all dependencies have been received
		 */
		public GraphReceiver(int index, Future<Void> finishHandler) {
			this.index = index;
			this.finishHandler = finishHandler;
		}

		@Override
		public void handle(Message<JsonObject> event) {
			SignalGraph found = SignalGraph.fromJson(event
					.body().encodePrettily());
			discoveredDependencies.add(index, found);

			// register for updates
			DependencyUpdateHandler handler = new DependencyUpdateHandler(
					"signals." + found.getId() + ".value",
					found);
			handler.apply(vertx.eventBus());

			if (discoveredDependencies.size() >= numberOfDependencies) {
				// a formatting function to make our output a little nicer
				Function<SignalGraph, String> justId = new Function<SignalGraph, String>() {
					
					@Override
					public String apply(SignalGraph graph) {
						return graph.getId();
					}
				};
				container.logger().info(id+" received dependency graphs from "+Iterables.transform(discoveredDependencies, justId));
				SignalGraph[] graphs = discoveredDependencies.toArray(new SignalGraph[0]);
				graph = new SignalGraph(id, graphs);
				finishHandler.setResult(null);
			}
		}
	}

	private class BlockHandler extends HandlerApplicator<Boolean> {

		public BlockHandler(String address) {
			super(address);
		}

		@Override
		public void handle(Message<Boolean> event) {
			blocked = event.body();
		}

	}

	/**
	 * Tracks update for a single dependency. This class will listen on a
	 * dependency's publish channel for updates and record the values as they
	 * are received.
	 * 
	 * @author bcard
	 * 
	 */
	private class DependencyUpdateHandler extends HandlerApplicator<Long> {

		private final SignalGraph symbol;

		public DependencyUpdateHandler(String address, SignalGraph symbol) {
			super(address);
			this.symbol = symbol;
		}

		@Override
		public void handle(Message<Long> event) {
			processDependencyUpdate(symbol, event.body());
		}
	}

	private void processDependencyUpdate(SignalGraph graph, Long value) {
		lastValues.put(graph, value);

		if (numberOfDependencies == 1) {
			updateValue(value);
			return;
		}

		if (lastValues.size() == numberOfDependencies) {
			// we've received an update from each dependency so
			// we should be clear to calculate the value.

			Long[] args = new Long[discoveredDependencies.size()];
			for (int i = 0; i < discoveredDependencies.size(); i++) {
				SignalGraph currentGraph = discoveredDependencies.get(i);
				args[i] = lastValues.get(currentGraph);
			}

			// just two values for now
			Long result = operator.call(args[0], args[1]);
			updateValue(result);
		}
	}

	/**
	 * Updates the valued stored by this signal and broadcasts the new value to
	 * all other signals.
	 * 
	 * @param newValue
	 *            the new value that this signal should represent
	 */
	private void updateValue(long newValue) {
		value = newValue;
		new PrintHandler(null).handle(null);
		if (!blocked) {
			vertx.eventBus().publish("signals." + id + ".value", value);
		}
	}

}
