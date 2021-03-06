package org.bcard.drega.command;

import org.bcard.drega.signal.Signal;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Container;

/**
 * A command to create a new signal with an initial value.
 * 
 * @author bcard
 * 
 */
public class CreateSignal implements ICommand {

	/**
	 * The value for the signal.
	 */
	private final long initialValue;

	/**
	 * The id of the signal.
	 */
	private final String id;

	/**
	 * Creates a new {@link CreateSignal} command.
	 * 
	 * @param identifier
	 *            the identifier
	 * @param initialValue
	 *            an initial value
	 */
	public CreateSignal(String identifier, long initialValue) {
		this.id = identifier;
		this.initialValue = initialValue;
	}

	@Override
	public void execute(Container container, Vertx vertx, Handler<AsyncResult<String>> done) {
		JsonObject config = new JsonObject();
		config.putString("id", id);
		config.putNumber("initialValue", initialValue);
		container.deployVerticle(Signal.class.getName(), config, done);
	}

	/**
	 * @return the initial value used to create the signal
	 */
	public long getInitialValue() {
		return initialValue;
	}

	/**
	 * @return the signal's identifier
	 */
	public String getId() {
		return id;
	}
}
