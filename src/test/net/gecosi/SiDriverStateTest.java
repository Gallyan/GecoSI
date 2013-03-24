/**
 * Copyright (c) 2013 Simon Denier
 */
package test.net.gecosi;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeoutException;

import net.gecosi.CommStatus;
import net.gecosi.CommWriter;
import net.gecosi.InvalidMessage;
import net.gecosi.SiDriverState;
import net.gecosi.SiHandler;
import net.gecosi.SiMessage;
import net.gecosi.SiMessageQueue;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * @author Simon Denier
 * @since Mar 15, 2013
 *
 */
public class SiDriverStateTest {

	private SiMessageQueue queue;

	@Mock
	private CommWriter writer;
	
	@Mock
	private SiHandler siHandler;

	@Before
	public void setUp() {
		MockitoAnnotations.initMocks(this);
		queue = new SiMessageQueue(5, 1);
	}
	
	@Test
	public void STARTUP_CHECK() throws Exception {
		queue.add(SiMessageFixtures.startup_answer);
		SiDriverState nextState = SiDriverState.STARTUP_CHECK.receive(queue, writer, siHandler);

		assertThat(nextState, equalTo(SiDriverState.EXTENDED_PROTOCOL_CHECK));
		verify(writer).write_debug(SiMessage.get_protocol_configuration);
	}

	@Test(expected=TimeoutException.class)
	public void STARTUP_CHECK_throwsTimeoutException() throws Exception {
		SiDriverState.STARTUP_CHECK.receive(queue, writer, siHandler);
	}

	@Test(expected=InvalidMessage.class)
	public void STARTUP_CHECK_throwsInvalidMessage() throws Exception {
		queue.add(SiMessage.ack_sequence);
		SiDriverState.STARTUP_CHECK.receive(queue, writer, siHandler);
	}

	@Test
	public void EXTENDED_PROTOCOL_CHECK() throws Exception {
		queue.add(SiMessageFixtures.config_answer);
		SiDriverState nextState = SiDriverState.EXTENDED_PROTOCOL_CHECK.receive(queue, writer, siHandler);

		assertThat(nextState, equalTo(SiDriverState.DISPATCH_READY));
		verify(siHandler).notify(CommStatus.READY);
	}

	@Test
	public void EXTENDED_PROTOCOL_CHECK_failsOnExtendedProtocolCheck() throws Exception {
		queue.add(SiMessageFixtures.no_ext_protocol_answer);
		SiDriverState nextState = SiDriverState.EXTENDED_PROTOCOL_CHECK.receive(queue, writer, siHandler);

		assertThat(nextState, equalTo(SiDriverState.EXTENDED_PROTOCOL_ERROR));
	}

}
