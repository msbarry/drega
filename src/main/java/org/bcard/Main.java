package org.bcard;

import java.util.Scanner;

import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.platform.Verticle;

public class Main extends Verticle {
	
	InputHandler handler;
	
	@Override
	public void start() {
		container.logger().info("Starting Application...");

		// start command processor
		container.deployVerticle("org.bcard.command.CommandProcessor");

		// command line interface
		handler = new InputHandler(vertx);
		vertx.setTimer(500, handler);
	}
	
	@Override
	public void stop() {
		container.logger().info("Closing Application");
	    handler.close();
	}
	
	private static class InputHandler implements Handler<Long> {
		
		private final Vertx vertx;
		private final Scanner in;
		
		public InputHandler(Vertx vertx) {
			this.vertx = vertx;
			in = new Scanner(System.in);
		}
		
		@Override
		public void handle(Long event) {
			System.out.print("> ");
			String input = in.nextLine();
			if (!input.isEmpty()) {
				vertx.eventBus().send("command", input, new Handler<Message>() {

					@Override
					public void handle(Message event) {
						vertx.setTimer(100, InputHandler.this);
					}

				});
			} else {
				vertx.setTimer(100, InputHandler.this);
			}
		}
		
		private void close() {
			in.close();
		}
	}
}
