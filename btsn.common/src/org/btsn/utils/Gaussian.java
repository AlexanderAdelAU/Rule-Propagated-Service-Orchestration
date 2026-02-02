package org.btsn.utils;

import java.util.Random;

public class Gaussian {
	private static Random fRandom = new Random();

	public static double getGaussian(double aMean, double aVariance) {
		return aMean + fRandom.nextGaussian() * aVariance;
	}

	private static void log(Object aMsg) {
		System.out.println(String.valueOf(aMsg));
	}

}
