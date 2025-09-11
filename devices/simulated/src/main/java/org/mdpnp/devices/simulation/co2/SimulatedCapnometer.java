/*******************************************************************************
 * Copyright (c) 2014, MD PnP Program
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package org.mdpnp.devices.simulation.co2;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.mdpnp.devices.DeviceClock;
import org.mdpnp.devices.math.DCT;
import org.mdpnp.devices.simulation.NumberWithGradient;
import org.mdpnp.devices.simulation.NumberWithJitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jeff Plourde
 *
 */
public class SimulatedCapnometer {

    private static final Logger log = LoggerFactory.getLogger(SimulatedCapnometer.class);

    private int count;

    protected int postIncrCount() {
        int count = this.count;
        this.count = ++this.count >= co2.length ? 0 : this.count;
        return count;
    }

    private final class DataPublisher implements Runnable {
        private final Number[] values = new Number[SAMPLES_PER_UPDATE];

        public DataPublisher() {
        }
        
        @Override
        public void run() {

            for (int i = 0; i < values.length; i++) {
                values[i] = SimulatedCapnometer.this.co2[postIncrCount()];
            }

            DeviceClock.Reading  t = deviceClock.instant();

            int rRate = respiratoryRate.intValue();
            int co2   = etCO2.intValue();

            receiveCO2(t, values, rRate, co2, FREQUENCY);
        }

    };

    protected void receiveCO2(DeviceClock.Reading time, Number[] co2, int respiratoryRate, int etCO2, int frequency) {

    }

    private final DeviceClock deviceClock;

    protected static final long UPDATE_PERIOD = 1000L;
    protected static final double MILLISECONDS_PER_SAMPLE = 50;
    protected static final int FREQUENCY = (int)(1000.0 / MILLISECONDS_PER_SAMPLE);
    protected static final int SAMPLES_PER_UPDATE = (int) Math.floor(UPDATE_PERIOD / MILLISECONDS_PER_SAMPLE);

    private static final double[] co2Coeffs = new double[] {
		152.0575191908128,-25.639345905973048,-86.35929352269913,11.424618921962939,-20.367647606139617,23.378253460643812,7.863835775389592,
		6.67672486780056,6.445936096703314,-10.830586308766076,0.34551755343634827,-9.60513099912234,1.5391176819142012,
		0.7379033163931021,2.2988478281240563,5.011848655357006,-0.5145409830110156,2.287453286158328,-3.6992850603616008,
		-1.203830447727006,-1.401363297255759,-0.5661032804636175,1.6318605469137126,-0.055673921623595114,1.8573832142510587,
		-0.3584154651709372,0.2424135879149204,-0.3975431727201282,-0.73424182765493,0.21747984814378357,-0.7658032506390209,
		0.43797180464607965,-0.46693682468473074,0.4314948594463935,0.24561464844444922,0.2382340938812413,0.5578559988848054,
		-0.26860906853125016,0.17352864789341588,-0.7586546875402287,-0.35824022061638944,-0.4273052258631351,0.0835862107252895,
		0.5086873564450236,0.5357612083576642,0.5235942341698114,0.02032252678099879,-0.4149056474764249,-0.7710495997062651,
		-0.47806888565051536,-0.23733723422787098,0.6969108248278894,0.43671139980689916,0.7553747102646997,-0.05176911612414758,
		-0.33346833573525236,-0.44341143372475006,-0.7019875225909462,0.3399788496992924,-0.15175917766293898,0.8423208209991081,
		0.008210441254869206,0.19736505746618366,-0.08782384251913544,-0.4772547183742542,0.2585132849921261,-0.5631537376300475,
		0.5603472129441145,-0.2550813797489281,0.34366775058788634,0.08429738380770917,-0.19039240726014175,0.42349144810452544,
		-0.5674736716772885,0.4676603377824327,-0.3946947169788891,0.23156756989736943,0.17459399463618994,-0.31771207657999756,
		0.4815792566626587,-0.5439913514298369,0.36935369588720113,-0.19293001233626086,-0.03698284410620354,0.4944402200811315,
		-0.37769347874800496,0.504159767984407,-0.4665478779308735,-0.05236536305406561,0.09856806165355401,-0.42877529518385166,
		0.6676094849925227,-0.30696291212659926,0.43653800693659345
    };

    private final double[] co2 = new double[co2Coeffs.length];

    private Number respiratoryRate = new NumberWithJitter(13, 1, 5);
    private Number etCO2 = new NumberWithJitter(29, 1, 5);

    private void initWaves() {
        DCT.idct(co2Coeffs, co2);
    }

    private ScheduledFuture<?> task;

    public void connect(ScheduledExecutorService executor) {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        long now = System.currentTimeMillis();
        task = executor.scheduleAtFixedRate(new DataPublisher(), UPDATE_PERIOD - now % UPDATE_PERIOD, UPDATE_PERIOD, TimeUnit.MILLISECONDS);
    }

    public void disconnect() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    public SimulatedCapnometer(final DeviceClock referenceClock) {
        deviceClock = new DeviceClock() {
            final DeviceClock dev=new DeviceClock.Metronome(UPDATE_PERIOD);
            @Override
            public Reading instant() {
                return new CombinedReading(referenceClock.instant(), dev.instant());
            }

        };
        initWaves();
    }

    public void setEndTidalCO2(Number targetEndTidalCO2) {
        this.etCO2 = new NumberWithGradient(etCO2, targetEndTidalCO2, 5);
        log.debug("Set etCO2 to " + this.etCO2);
    }

    public void setRespirationRate(Number targetRespirationRate) {
        this.respiratoryRate = new NumberWithGradient(respiratoryRate, targetRespirationRate, 1);
        log.debug("Set respiratoryRate to " + this.respiratoryRate);
    }

}
