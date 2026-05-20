package org.mdpnp.devices.simulation;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NumberWithGradientTest {

    private static final Logger log = LoggerFactory.getLogger(NumberWithGradientTest.class);

    @Test
    public void testGradientWithNoise() throws Exception {

        NumberWithGradient g = new NumberWithGradient(0, 100, 10, 1);

        for(int idx=0; idx<10; idx++) {
            double i = g.doubleValue();
            log.info("GradientValue=" + i);
            double v = 10 * idx;
            Assert.assertTrue("Invalid value at iteration " + idx, Math.abs(i - v) <= 1);
        }
        for(int idx=0; idx<10; idx++) {
            double i = g.doubleValue();
            log.info("GradientValue=" + i);
            Assert.assertTrue("Invalid value at iteration " + idx, Math.abs(i - 100) <= 1);
        }
    }


    @Test
    public void testGradientFloat() throws Exception {

        NumberWithGradient g = new NumberWithGradient(0, 100.0, 10.0, 0);

        for(int idx=0; idx<10; idx++) {
            double i = g.floatValue();
            log.info("GradientValue=" + i);
            double v = 10 * idx;
            Assert.assertEquals("Invalid value at iteration " + idx, v, i, 0.0001);
        }

        for(int idx=0; idx<10; idx++) {
            double i = g.floatValue();
            log.info("GradientValue=" + i);
            Assert.assertEquals("Invalid value at iteration " + idx, 100, i, 0.0001);
        }
    }

    @Test
    public void testUpdateFloat() throws Exception {

        NumberWithGradient g = new NumberWithGradient(15, 15, 2, 2);

        for(int idx=0; idx<10; idx++) {
            double i = g.floatValue();
            log.info("Step 1: GradientValue=" + i);
            Assert.assertTrue("Step 1: Invalid value at iteration " + idx, Math.abs(i - 15) <= 2);
        }

        NumberWithGradient g2 = new NumberWithGradient(g, 15, 2, 2);

        for(int idx=0; idx<10; idx++) {
            double i = g2.floatValue();
            log.info("Step 2: GradientValue=" + i);
            Assert.assertTrue("Step 2: Invalid value at iteration " + idx, Math.abs(i - 15) <= 2);
        }
    }

    @Test
    public void testApplyValue() throws Exception {

        // make sure that we can re-assign the gradient without loosing the original start value.
        // this is to emulate the case when the user changes the simulator controls via the gui
        // and that sends increments for every drag of the slider.
        //
        Number initial = Integer.valueOf(15);

        for (int idx = 1; idx < 10; idx++) {
            Number target = Integer.valueOf(15 + idx);
            initial = new NumberWithGradient(initial, target, 1);
            log.info("change initial value to " + initial);
        }

        Assert.assertEquals("failed to preserve original start value", 15, initial.intValue());
    }

    @Test
    public void testNestedUpdates() throws Exception {

        //
        // acid test to mock the update sequence all the way from the update loop
        // The loop simulates the logic in the typical
        // GlobalSimulationObjectiveListener::simulatedNumeric implementation
        //

        double [] sequence = new double[] { 1.0, 2.0, 3.0, 5.0, 5.0, 5.0, 6.0 };
        Number initial = Integer.valueOf(0);

        for(int i=0; i<sequence.length; i++) {
            // this loop simulates update callbacks. GlobalSimulationObjectiveListener
            // recreates NumberWithGradient upon receipt of new value wrapping old target
            initial = new NumberWithGradient(initial, sequence[i], 1.0, 0);

            // get the intermediate value. This triggers internal update of
            // the state.
            // This is the call made by SimulatedDevice::getNumeric()
            double intermediate = initial.doubleValue();
            log.info("Step " + i + ": intermediate=" + intermediate);

            Assert.assertEquals(i, intermediate, 0.001);
        }
    }
}
