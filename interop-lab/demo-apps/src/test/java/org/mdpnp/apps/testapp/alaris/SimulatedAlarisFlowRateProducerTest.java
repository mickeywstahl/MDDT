package org.mdpnp.apps.testapp.alaris;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SimulatedAlarisFlowRateProducerTest {
	
	@Test
	public void testFlowRateRamping1() {
		SingleSimAlaris.FlowRateProducer producer=new SingleSimAlaris().new FlowRateProducer(10,0);
		producer.start();
		producer.setTargetRate(100);
		float initial=producer.getCurrentRate();
		assertEquals(0, initial, 0);	//Right at the beginning, flow rate should not have changed because delay has not passed.
		try {
			Thread.sleep(200);
		} catch (InterruptedException ie) {}
		try {
			float nextExpected=10;
			while(nextExpected<101) {
				assertEquals(nextExpected, producer.getCurrentRate(),0);
				nextExpected+=10;
				Thread.sleep(1000);
			}
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}		
	}
	
	@Test
	public void testFlowRateRamping2() {
		//Test that response is basically immediate when delay is 0 and ramp rate is very high.
		SingleSimAlaris.FlowRateProducer producer=new SingleSimAlaris().new FlowRateProducer(1000,0);
		producer.start();
		producer.setTargetRate(100);
		//Give it a few moments...
		try {
			Thread.sleep(200);
		} catch (InterruptedException ie) {}
		float initial=producer.getCurrentRate();
		assertEquals(100,initial,0);
	}

}
