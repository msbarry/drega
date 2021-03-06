package org.bcard.integration.java;

import static org.vertx.testtools.VertxAssert.testComplete;

import java.util.concurrent.atomic.AtomicBoolean;

import org.bcard.drega.command.BlockSignal;
import org.bcard.drega.command.CombineSymbols;
import org.bcard.drega.command.CreateSignal;
import org.bcard.drega.command.GlitchSignal;
import org.bcard.drega.command.Increment;
import org.bcard.drega.command.MapSignal;
import org.bcard.drega.command.PrintGraph;
import org.bcard.drega.command.PrintSignal;
import org.bcard.drega.signal.CombineOperator;
import org.junit.Test;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.testtools.TestVerticle;
import org.vertx.testtools.VertxAssert;

/**
 * Integration tests for the signals so we can build more complicated scenarios.
 * 
 * @author bcard
 * 
 */
public class SignalIntegrationTest extends TestVerticle {

	@Test
	public void testIncrement() {
		createSignalX(new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				assertValueWillBe(1L, "x");

				// send the increment command
				Increment increment = new Increment("x");
				increment.execute(container, vertx, new DummyHandler());
			}
		});
	}
	
	@Test
	public void testPrintGraph() {
		createSignalX(new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				PrintGraph print = new PrintGraph("x");
				print.execute(container, vertx, new Handler<AsyncResult<String>>(){

					@Override
					public void handle(AsyncResult<String> event) {
						testComplete();
					}
				});
			}
		});
	}
	
	@Test
	public void testPrint() {
		createSignalX(new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				PrintSignal print = new PrintSignal("x");
				print.execute(container, vertx, new Handler<AsyncResult<String>>(){

					@Override
					public void handle(AsyncResult<String> event) {
						testComplete();
					}
				});
			}
		});
	}
	
	@Test
	public void testMapSingle() {
		createSignalX(thenMapYToX(new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				// check for increase on y
				assertValueWillBe(1L, "y");

				// send the increment command
				Increment increment = new Increment("x");
				increment.execute(container, vertx, new DummyHandler());
			}
		}));
	}
	
	@Test
	public void testSimpleCombine() {
		createSignalX(new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				CreateSignal create = new CreateSignal("y", 1);
				create.execute(container, vertx, thenZEqualsXPlusY(thenIncrementX(new Handler<AsyncResult<String>>() {

					@Override
					public void handle(AsyncResult<String> event) {
						// we should get an update after y is incremented
						assertValueWillBe(3L, "z");

						Increment increment = new Increment("y");
						increment.execute(container, vertx, new DummyHandler());
					}
				})));
			}
		});
	}
	
	@Test
	public void testBlock() {
		createSignalX(new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				vertx.eventBus().registerHandler("signals.x.value", new Handler<Message<Long>>() {

					@Override
					public void handle(Message<Long> event) {
						VertxAssert.fail();
						testComplete();
					}
				});
				
				BlockSignal block = new BlockSignal("x", true);
				block.execute(container, vertx, new Handler<AsyncResult<String>>() {

					@Override
				    public void handle(AsyncResult<String> event) {
					Increment increment = new Increment("x");
					increment.execute(container, vertx,new Handler<AsyncResult<String>>() {

							@Override
							public void handle(AsyncResult<String> event) {
								createSignal("y", new Handler<AsyncResult<String>>() {

									@Override
									public void handle(AsyncResult<String> event) {

										// by this point the
										// event bus has had
										// enough time to send
										// out any messages it
										// needs to, if we get
										// here we can call
										// it a pass
										testComplete();
									}
								});
							}
						});
					}
					
				});
			}
			
		});
	}
	
	@Test
	public void testSimpleGlitchAvoidance() {
		/*
		 * z should never be odd:
		 * 
		 * x = 0
		 * y = x
		 * z = x + y
		 */
		
		createSignalX(thenMapYToX(thenZEqualsXPlusY(new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				vertx.eventBus().registerHandler("signals.z.value", new Handler<Message<JsonObject>>() {

					@Override
					public void handle(Message<JsonObject> event) {
						Long value = event.body().getLong("value");
						VertxAssert.assertFalse("Value equals 1", value.equals(Long.valueOf(1L)));
						VertxAssert.assertFalse("Value equals 3", value.equals(Long.valueOf(3L)));
						VertxAssert.assertFalse("Value equals 5", value.equals(Long.valueOf(5L)));
						if (value.equals(Long.valueOf(6))) {
							testComplete();
						}
					}
				});

				// we will incremented x three times, z should have
				// never had the values 1, 3, or 5
				Increment increment = new Increment("x");
				increment.execute(container, vertx,(thenIncrementX(thenIncrementX(new DummyHandler()))));
			}

		})));

	}
	
	@Test
	public void testDisableGlitchAvoidance() {
		final AtomicBoolean oddValueFound = new AtomicBoolean(false);
		createSignalX(thenMapYToX(thenZEqualsXPlusY(new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				vertx.eventBus().registerHandler("signals.z.value", new Handler<Message<JsonObject>>() {

					@Override
					public void handle(Message<JsonObject> event) {
						Long value = event.body().getLong("value");
						if (value.equals(Long.valueOf(1)) || 
							value.equals(Long.valueOf(3)) || 
							value.equals(Long.valueOf(5))) {
							oddValueFound.set(true);
						}
						
						if (value.equals(Long.valueOf(6))) {
							VertxAssert.assertTrue("No glitches detected", oddValueFound.get());
							testComplete();
						}
					}
				});
				
				GlitchSignal glitches = new GlitchSignal("z", false);
				glitches.execute(container, vertx, new Handler<AsyncResult<String>>() {

					@Override
					public void handle(AsyncResult<String> event) {
						// we will incremented x three times, z should have
						// had the values 1, 3, or 5 since we are not avoiding 
						// glitches
						Increment increment = new Increment("x");
						increment.execute(container, vertx,(thenIncrementX(thenIncrementX(new DummyHandler()))));
					}
				});
				}
		})));
	}
	
	// --------------- Helper Methods ---------------- //

	/**
	 * Start function used to kick off our tests.
	 */
	@Override
	public void start() {
		// Make sure we call initialize() - this sets up the assert stuff so
		// assert functionality works correctly
		initialize();
		// Deploy the module - the System property `vertx.modulename` will
		// contain the name of the module so you
		// don't have to hardecode it in your tests
		startTests();
	}
	
	/**
	 * Creates a new signal with an id of {@code x} and a value of {@code 0}.
	 * 
	 * @param handler
	 *            the handler to be called after the signal has been created
	 */
	private void createSignalX(Handler<AsyncResult<String>> handler) {
		CreateSignal create = new CreateSignal("x", 0);
		create.execute(container, vertx,  handler);
	}
	
	/**
	 * Creates a new signal with an id of {@code y} and a value of {@code 1}.
	 * 
	 * @param handler
	 *            the handler to be called after the signal has been created
	 */
	private void createSignal(String id, Handler<AsyncResult<String>> handler) {
		CreateSignal create = new CreateSignal(id, 1);
		create.execute(container, vertx,  handler);
	}
	
	private Handler<AsyncResult<String>> thenMapYToX(final Handler<AsyncResult<String>> handler) {
		return new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				
				MapSignal create = new MapSignal("y", "x");
				create.execute(container, vertx, handler);
			}
		};
	}
	
	private Handler<AsyncResult<String>> thenZEqualsXPlusY(final Handler<AsyncResult<String>> handler) {
		return new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				CombineSymbols create = new CombineSymbols("z", "x", "y", CombineOperator.ADD);
				create.execute(container, vertx, handler);
			}
		};
	}
	
	private Handler<AsyncResult<String>> thenIncrementX(final Handler<AsyncResult<String>> handler) {
		return new Handler<AsyncResult<String>>() {

			@Override
			public void handle(AsyncResult<String> event) {
				Increment increment = new Increment("x");
				increment.execute(container, vertx, handler);
			}
		};
	}

	private static final class DummyHandler implements
			Handler<AsyncResult<String>> {

		@Override
		public void handle(AsyncResult<String> event) {
			// do nothing
		}
	}

	private void assertValueWillBe(final Long value, String id) {
		vertx.eventBus().registerHandler("signals."+id+".value", new Handler<Message<JsonObject>>() {

			@Override
			public void handle(Message<JsonObject> event) {
				Long msgVal = event.body().getLong("value");
				if (!msgVal.equals(value)) {
					return;
				}
				VertxAssert.assertEquals(value, msgVal);
				testComplete();
			}
		});
	}
}
