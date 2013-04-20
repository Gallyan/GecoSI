/**
 * Copyright (c) 2013 Simon Denier
 */
package net.gecosi.internal;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import net.gecosi.CommStatus;
import net.gecosi.SiHandler;
import net.gecosi.dataframe.Si5DataFrame;
import net.gecosi.dataframe.Si8_9DataFrame;

/**
 * @author Simon Denier
 * @since Mar 10, 2013
 *
 */
public enum SiDriverState {
	
	STARTUP {
		public SiDriverState send(CommWriter writer) throws IOException {
			writer.write(SiMessage.startup_sequence);
			return STARTUP_CHECK;
		}
	},

	STARTUP_CHECK {
		public SiDriverState receive(SiMessageQueue queue, CommWriter writer, SiHandler siHandler)
				throws IOException, InterruptedException, TimeoutException, InvalidMessage {
			pollAnswer(queue, SiMessage.SET_MASTER_MODE);
			return GET_CONFIG.send(writer);
		}
	},

	STARTUP_TIMEOUT {
		public boolean isError() { return true; }
		public String status() {
			return "Master station did not answer to startup sequence (high/low baud)";
		}
	},

	GET_CONFIG {
		public SiDriverState send(CommWriter writer) throws IOException {
			writer.write(SiMessage.get_protocol_configuration);
			return EXTENDED_PROTOCOL_CHECK;
		}
	},

	EXTENDED_PROTOCOL_CHECK {
		public SiDriverState receive(SiMessageQueue queue, CommWriter writer, SiHandler siHandler)
				throws IOException, InterruptedException, TimeoutException, InvalidMessage {
			SiMessage message = pollAnswer(queue, SiMessage.GET_SYSTEM_VALUE);
			if( (message.sequence(6) & EXTENDED_PROTOCOL_MASK) != 0 ) {
				writer.write(SiMessage.beep_twice);
				siHandler.notify(CommStatus.ON);
				return DISPATCH_READY;
			} else {
				return EXTENDED_PROTOCOL_ERROR;
			}
		}
	},

	EXTENDED_PROTOCOL_ERROR {
		public boolean isError() { return true; }
		public String status() {
			return "Master station should be configured with extended protocol";
		}
	},
	
	DISPATCH_READY {
		public SiDriverState receive(SiMessageQueue queue, CommWriter writer, SiHandler siHandler)
				throws IOException, InterruptedException, TimeoutException, InvalidMessage {
			siHandler.notify(CommStatus.READY);
			SiMessage message = queue.take();
			siHandler.notify(CommStatus.PROCESSING);
			switch (message.commandByte()) {
			case SiMessage.SI_CARD_5_DETECTED:
				GecoSILogger.stateChanged(READ_SICARD_5.name());
				return READ_SICARD_5.send(writer);
			case SiMessage.SI_CARD_8_PLUS_DETECTED:
				GecoSILogger.stateChanged(RETRIEVE_SICARD_8_9_DATA.name());
				return RETRIEVE_SICARD_8_9_DATA.receive(queue, writer, siHandler);
			case SiMessage.BEEP:
				return DISPATCH_READY;
			default:
				GecoSILogger.debug("Unexpected message " + message.toString());
				return DISPATCH_READY;
			}
		}
	},
	
	READ_SICARD_5 {
		public SiDriverState send(CommWriter writer) throws IOException {
			writer.write(SiMessage.read_sicard_5);
			return WAIT_SICARD_5_DATA;
		}
	},
	
	WAIT_SICARD_5_DATA {
		public SiDriverState receive(SiMessageQueue queue, CommWriter writer, SiHandler siHandler)
				throws IOException, InterruptedException {
			try {
				SiMessage message = queue.timeoutPoll();
				if( message.check(SiMessage.GET_SI_CARD_5) ){
					siHandler.notify(new Si5DataFrame(message));
					return ACK_READ.send(writer);
				} else {
					return errorFallback(siHandler, "Invalid message");
				}
			} catch (TimeoutException e) {
				 return errorFallback(siHandler, "Timeout");
			}
		}
	},
	
	RETRIEVE_SICARD_8_9_DATA {
		public SiDriverState receive(SiMessageQueue queue, CommWriter writer, SiHandler siHandler)
				throws IOException, InterruptedException {
			try {
				SiMessage[] retrieval_messages = new SiMessage[] {
						SiMessage.read_sicard_8_plus_b0, SiMessage.read_sicard_8_plus_b1
				};
				SiMessage[] data_messages = new SiMessage[retrieval_messages.length];
				for (int i = 0; i < retrieval_messages.length; i++) {
					SiMessage send_message = retrieval_messages[i];
					writer.write(send_message);
					SiMessage received_message = queue.timeoutPoll();
					if( received_message.check(send_message.commandByte()) ){
						data_messages[i] = received_message;
					} else {
						return errorFallback(siHandler, "Invalid message");
					}		
				}
				siHandler.notify(new Si8_9DataFrame(data_messages));
				return ACK_READ.send(writer);
			} catch (TimeoutException e) {
				 return errorFallback(siHandler, "Timeout");
			}
		}		
	},
	
	ACK_READ {
		public SiDriverState send(CommWriter writer) throws IOException {
			writer.write(SiMessage.ack_sequence);
			return WAIT_SICARD_REMOVAL;
		}		
	},
	
	WAIT_SICARD_REMOVAL {
		public SiDriverState receive(SiMessageQueue queue, CommWriter writer, SiHandler siHandler)
				throws IOException, InterruptedException {
			try {
				SiMessage message = queue.timeoutPoll();
				if( message.check(SiMessage.SI_CARD_REMOVED) ){
					 return DISPATCH_READY;
				 } else {
					 return errorFallback(siHandler, "Invalid message");
				 }
			} catch (TimeoutException e) {
				return errorFallback(siHandler, "Timeout");
			}
		}		
	};

	private static final int EXTENDED_PROTOCOL_MASK = 1;

	public SiDriverState send(CommWriter writer) throws IOException {
		wrongCall();
		return this;
	}

	public SiDriverState receive(SiMessageQueue queue, CommWriter writer, SiHandler siHandler)
			throws IOException, InterruptedException, TimeoutException, InvalidMessage {
		wrongCall();
		return this;
	}
	
	private void wrongCall() {
		throw new RuntimeException(String.format("This method should not be called on %s", this.name()));
	}

	public boolean isError() {
		return false;
	}
	
	public String status() {
		return name();
	}

	public void checkAnswer(SiMessage message, byte command) throws InvalidMessage {
		if( ! message.check(command) ){
			throw new InvalidMessage(message);
		}
	}

	public SiMessage pollAnswer(SiMessageQueue queue, byte command)
			throws InterruptedException, TimeoutException, InvalidMessage {
		SiMessage message = queue.timeoutPoll();
		checkAnswer(message, command);
		return message;
	}

	public SiDriverState errorFallback(SiHandler siHandler, String errorMessage) {
		GecoSILogger.error(errorMessage);
		siHandler.notify(CommStatus.PROCESSING_ERROR);
		return DISPATCH_READY;
	}

}