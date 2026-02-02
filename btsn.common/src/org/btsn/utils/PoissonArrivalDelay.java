package org.btsn.utils;

/*
 * Assumes lamda arrivals per second
 */
public class PoissonArrivalDelay implements Runnable {
	private Integer lambda;
	private Integer period;

	public PoissonArrivalDelay(Integer lambda, Integer period) {
		// this.buildVersion = buildVersion;
		this.lambda = lambda;
		this.period = period;
	}

	public static void poissonDelay(Integer lambda, Integer period) {
		/*
		 * Lambda is the rate and the period is interval the rate is measured in
		 */

		long interval = period / poissonRandomNumber(lambda);
		// System.err.println("Interval=" + interval);

		try {
			Thread.sleep(interval);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private static Integer poissonRandomNumber(Integer lambda) {
		double L = Math.exp(-lambda);
		int k = 0;
		double p = 1;
		do {
			k = k + 1;
			double u = Math.random();
			p = p * u;
		} while (p > L);
		return k - 1;
	}

	public void run() {
		poissonDelay(lambda, period);
	}
}
