package org.bcard.drega.signal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Function;
import com.google.common.collect.Iterables;

/**
 * A chain of different graphs. The chain is updated as events propagate through
 * signals. This class stores the ID of the signal that it's passed through and
 * the event counter for that signal. The entire dependency graph is not stored.
 * This is to make this class more space efficient. The entire chain is not
 * necessary because each signal has the graphs of all of it's dependencies.
 * <p>
 * Note that this class is mutable.
 * @author bcard
 * 
 */
@JsonDeserialize(using = SignalChain.Deserializer.class)
@JsonInclude(Include.NON_EMPTY)
public class SignalChain {

	@JsonProperty
	private final List<GraphAndCounter> entries = new LinkedList<>();

	/**
	 * Creates an empty chain with no dependencies.
	 */
	public SignalChain() {
	}

	/**
	 * Creates a new {@link SignalChain} with an initial signal but no event
	 * counters for that signal.
	 * 
	 * @param head the initial signal
	 */
	public SignalChain(SignalGraph head) {
		entries.add(new GraphAndCounter(head.getId(), -1));
	}

	/**
	 * Creates a new {@link SignalChain} and sets the event counter to the given
	 * value.
	 * 
	 * @param head the intial signal
	 * @param eventCounter the event counter for that signal
	 */
	public SignalChain(SignalGraph head, int eventCounter) {
		entries.add(new GraphAndCounter(head.getId(), eventCounter));
	}

	/**
	 * Copy constructor.
	 * 
	 * @param other
	 *            the SignalChain to copy
	 */
	public SignalChain(SignalChain other) {
		entries.addAll(other.entries);
	}

	/**
	 * Returns all of the conflicts that this chain has in common with the
	 * {@code other} chain. Note that the event counters are not checked by this
	 * method, any common dependency is returned as a conflict.
	 * 
	 * @param other
	 * @return
	 */
	public List<String> getConflicts(SignalChain other) {
		List<String> theseValues = toList();
		List<String> otherValues = other.toList();

		theseValues.retainAll(otherValues);
		List<String> returnValue = new ArrayList<>();
		for (String candidate : theseValues) {
			String nextForThis = nextSignal(candidate);
			String nextForOther = other.nextSignal(candidate);

			if (nextForThis == null && nextForOther != null || nextForThis != null
					&& !nextForThis.equals(nextForOther)) {
				returnValue.add(candidate);
			}
		}
		return returnValue;
	}

	/**
	 * Searches through the entries and finds the signal that follows the given
	 * signal. This is the signal that the given signal is sending it's value
	 * to, which appears after it in the list of entries.
	 * 
	 * @param signal
	 *            the signal to seach for
	 * @return the signal the given signal will send it's events to, or
	 *         {@code null} if the given signal cannot be found or is found to
	 *         have sent any events in this chain
	 */
	private String nextSignal(String signal) {
		ListIterator<GraphAndCounter> iterator = entries.listIterator();
		while (iterator.hasNext()) {
			GraphAndCounter current = iterator.next();
			if (current.getId().equals(signal) && iterator.hasNext()) {
				return iterator.next().getId();
			}
		}

		return null;
	}

	/**
	 * Returns the event counter set for the graph with the given id or -1 if
	 * the event counter has not been set or cannot be located.
	 * 
	 * @param graph
	 * @return
	 */
	public int getEventCounterFor(String graph) {
		for (GraphAndCounter entry : entries) {
			if (entry.getId().equals(graph)) {
				return entry.getCounter();
			}
		}

		return -1;
	}

	/**
	 * Adds a graph to the head of this chain of graphs.
	 * 
	 * @param link
	 *            the graph to add
	 */
	public void chain(SignalGraph link) {
		chain(link, -1);
	}

	/**
	 * Adds a graph to the head of this chain of graphs.
	 * 
	 * @param link
	 *            the graph to add
	 * @param eventCounter
	 *            the event counter for the given {@code link}
	 */
	public void chain(SignalGraph link, int eventCounter) {
		entries.add(new GraphAndCounter(link.getId(), eventCounter));
	}

	/**
	 * Checks to see if this chain contains a signal with the given ID.
	 * 
	 * @param signal
	 *            the signal to search for
	 * @return {@code true} if the graph contains the given signal,
	 *         {@code false} if it does not
	 */
	public boolean contains(String signal) {
		boolean returnValue = false;
		for (GraphAndCounter entry : entries) {
			if (entry.getId().equals(signal)) {
				returnValue = true;
				break;
			}
		}
		return returnValue;
	}

	public String getLast() {
		return entries.get(entries.size() - 1).id;
	}

	/**
	 * Returns a JSON representation of this object. Use the
	 * {@link #fromJson(String)} method to convert this representation back into
	 * a {@link SignalChain}.
	 * 
	 * @return a JSON representation of this object
	 */
	public String toJson() {
		ObjectMapper mapper = new ObjectMapper();
		String returnValue = "";
		try {
			returnValue = mapper.writer(new DefaultPrettyPrinter()).writeValueAsString(this);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}
		return returnValue;
	}

	/**
	 * Creates a new {@link SignalChain} from a JSON representation. This can be
	 * used in conjunction with the {@link #toJson()} method to
	 * serialize/deserialize this object.
	 * 
	 * @param json
	 *            a serialized {@link SignalChain}
	 * @return the JSON converted to a new {@link SignalChain}
	 */
	public static SignalChain fromJson(String json) {
		ObjectMapper mapper = new ObjectMapper();
		SignalChain chain = null;
		try {
			chain = mapper.readValue(json, SignalChain.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return chain;
	}

	/**
	 * Converts the {@link #graph} to a list of {@code String} IDs
	 * 
	 * @return a {@link List} of IDs
	 */
	public List<String> toList() {
		List<String> list = new ArrayList<>();
		for (GraphAndCounter entry : entries) {
			list.add(entry.getId());
		}

		return list;
	}

	@Override
	public String toString() {
		Iterable<String> vals = Iterables.transform(toList(), new Function<String, String>() {

			public String apply(String val) {
				int count = getEventCounterFor(val);
				return "[" + val + "," + Integer.toString(count) + "]";
			}
		});
		return vals.toString();
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((entries == null) ? 0 : entries.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		SignalChain other = (SignalChain) obj;
		if (entries == null) {
			if (other.entries != null)
				return false;
		} else if (!entries.equals(other.entries))
			return false;
		return true;
	}

	/**
	 * Deterializes this class from JSON.
	 * 
	 * @author bcard
	 *
	 */
	public static class Deserializer extends JsonDeserializer<SignalChain> {

		@Override
		public SignalChain deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException,
				JsonProcessingException {
			ObjectCodec oc = parser.getCodec();
			JsonNode node = oc.readTree(parser);
			SignalChain returnValue = new SignalChain(new SignalGraph(""));
			returnValue.entries.clear();
			JsonNode entries = node.get("entries");
			for (JsonNode entry : entries) {
				String id = entry.get("id").asText();
				int counter = entry.get("counter").asInt();
				GraphAndCounter val = new GraphAndCounter(id, counter);

				returnValue.entries.add(val);
			}
			return returnValue;
		}

	}

	/**
	 * A simple data structure that stores a graph and a counter.
	 * 
	 * @author bcard
	 */
	private static class GraphAndCounter {
		@JsonProperty
		private final String id;

		@JsonProperty
		private final int counter;

		public GraphAndCounter(String id, int counter) {
			this.id = id;
			this.counter = counter;
		}

		public String getId() {
			return id;
		}

		public int getCounter() {
			return counter;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + counter;
			result = prime * result + ((id == null) ? 0 : id.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			GraphAndCounter other = (GraphAndCounter) obj;
			if (counter != other.counter)
				return false;
			if (id == null) {
				if (other.id != null)
					return false;
			} else if (!id.equals(other.id))
				return false;
			return true;
		}

	}

}
