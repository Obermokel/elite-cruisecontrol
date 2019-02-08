package borg.ed.cruisecontrol;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.util.CloseableIterator;

import borg.ed.universe.UniverseApplication;
import borg.ed.universe.constants.PlanetClass;
import borg.ed.universe.data.Coord;
import borg.ed.universe.model.Body;
import borg.ed.universe.service.UniverseService;

@Configuration
@Import(UniverseApplication.class)
public class TestTerraforming {

	static final Logger logger = LoggerFactory.getLogger(TestTerraforming.class);

	private static final ApplicationContext APPCTX = new AnnotationConfigApplicationContext(TestTerraforming.class);

	public static final BigDecimal GRAVITATIONAL_CONSTANT = new BigDecimal("0.0000000000667408"); // m³ / (kg * s²)
	public static final BigDecimal EARTH_RADIUS_KM = new BigDecimal("6378"); // km
	public static final BigDecimal EARTH_MASS_KG = new BigDecimal("5974000000000000000000000"); // kg
	public static final BigDecimal EARTH_DENSITY_G_CM3 = new BigDecimal("5.515"); // g/cm³
	public static final BigDecimal EARTH_GRAVITY_M_S2 = new BigDecimal("9.81"); // m/s²

	private static final BigDecimal FACTOR_KM_TO_M = new BigDecimal(1000); // km -> m
	private static final BigDecimal FACTOR_KG_TO_G = new BigDecimal(1000); // kg -> g
	private static final BigDecimal FACTOR_KM3_TO_CM3 = new BigDecimal(100000).pow(3); // km³ -> cm³

	public static void main(String[] args) {
		//		BigDecimal earthMasses = new BigDecimal("0.6735");
		//		BigDecimal radiusKm = new BigDecimal("5402");

		//		BigDecimal massKg = earthMasses.multiply(EARTH_MASS_KG);
		//		BigDecimal volumeKm3 = BigDecimal.valueOf(4.0 / 3.0).multiply(BigDecimal.valueOf(Math.PI)).multiply(radiusKm.pow(3)); // V = 4/3 * PI * r^3
		//		BigDecimal expectedGravityM_s2 = (GRAVITATIONAL_CONSTANT.multiply(massKg)).divide(radiusKm.multiply(FACTOR_KM_TO_M).pow(2), 2, BigDecimal.ROUND_HALF_UP); // g = (G * m) / r²
		//		BigDecimal expectedGravityG = expectedGravityM_s2.divide(EARTH_GRAVITY_M_S2, 2, BigDecimal.ROUND_HALF_UP);

		//		System.out.println("expectedGravityG=" + expectedGravityG);

		UniverseService universeService = APPCTX.getBean(UniverseService.class);

		List<BigDecimal> sortedDistanceToArrivalLs = new ArrayList<>();
		List<BigDecimal> sortedSemiMajorAxisAu = new ArrayList<>();
		List<BigDecimal> sortedEarthMasses = new ArrayList<>();
		List<BigDecimal> sortedRadiusKm = new ArrayList<>();
		List<BigDecimal> sortedGravityG = new ArrayList<>();
		try (CloseableIterator<Body> it = universeService.streamPlanetsNear(new Coord(), 10000, true, Arrays.asList(PlanetClass.WATER_WORLD))) {
			while (it.hasNext()) {
				Body b = it.next();
				try {
					BigDecimal distanceToArrivalLs = b.getDistanceToArrivalLs();
					BigDecimal semiMajorAxisAu = b.getSemiMajorAxis();
					BigDecimal earthMasses = b.getEarthMasses();
					BigDecimal radiusKm = b.getRadiusKm();
					if (radiusKm.intValue() > 100_000) {
						radiusKm = radiusKm.divide(new BigDecimal(1000), 0, BigDecimal.ROUND_HALF_UP);
					}
					BigDecimal gravityG = b.getGravityG();
					if (gravityG.intValue() > 8) {
						gravityG = gravityG.divide(EARTH_GRAVITY_M_S2, 2, BigDecimal.ROUND_HALF_UP);
					}

					if (distanceToArrivalLs != null && semiMajorAxisAu != null && earthMasses != null && radiusKm != null && gravityG != null && semiMajorAxisAu.floatValue() >= 0.01) {
						if (semiMajorAxisAu.longValue() > 100) {
							//logger.warn("semiMajorAxisAu=" + semiMajorAxisAu + " of " + b.getName() + " is extremely high!");
							continue;
						} else if (semiMajorAxisAu.longValue() > 10) {
							//logger.warn("semiMajorAxisAu=" + semiMajorAxisAu + " of " + b.getName() + " is quite high!");
							continue;
						}

						BigDecimal massKg = earthMasses.multiply(EARTH_MASS_KG);
						BigDecimal volumeKm3 = BigDecimal.valueOf(4.0 / 3.0).multiply(BigDecimal.valueOf(Math.PI)).multiply(radiusKm.pow(3)); // V = 4/3 * PI * r^3
						BigDecimal expectedGravityM_s2 = (GRAVITATIONAL_CONSTANT.multiply(massKg)).divide(radiusKm.multiply(FACTOR_KM_TO_M).pow(2), 2, BigDecimal.ROUND_HALF_UP); // g = (G * m) / r²
						BigDecimal expectedGravityG = expectedGravityM_s2.divide(EARTH_GRAVITY_M_S2, 2, BigDecimal.ROUND_HALF_UP);

						BigDecimal diffG = expectedGravityG.subtract(gravityG).abs();
						if (diffG.floatValue() > 0.01) {
							//logger.warn("Expected " + expectedGravityG + " G, but was " + gravityG + "G: earthMasses=" + earthMasses + ", radiusKm=" + radiusKm + ", name=" + b.getName());
							continue;
						}

						sortedDistanceToArrivalLs.add(distanceToArrivalLs.setScale(2, BigDecimal.ROUND_HALF_UP));
						sortedSemiMajorAxisAu.add(semiMajorAxisAu.setScale(2, BigDecimal.ROUND_HALF_UP));
						sortedEarthMasses.add(earthMasses.setScale(4, BigDecimal.ROUND_HALF_UP));
						sortedRadiusKm.add(radiusKm.setScale(0, BigDecimal.ROUND_HALF_UP));
						sortedGravityG.add(gravityG.setScale(2, BigDecimal.ROUND_HALF_UP));
					}
				} catch (Exception e) {
					logger.warn("Failed to process " + b + ": " + e);
				}
			}
		}
		Collections.sort(sortedDistanceToArrivalLs, new BigDecimalFloatComparator());
		Collections.sort(sortedSemiMajorAxisAu, new BigDecimalFloatComparator());
		Collections.sort(sortedEarthMasses, new BigDecimalFloatComparator());
		Collections.sort(sortedRadiusKm, new BigDecimalFloatComparator());
		Collections.sort(sortedGravityG, new BigDecimalFloatComparator());

		logger.info("Statistics of " + sortedEarthMasses.size() + " bodies:");
		logger.info("distanceToArrivalLs:    min=" + min(sortedDistanceToArrivalLs) + " / 1%=" + top(sortedDistanceToArrivalLs, 1) + " / 5%=" + top(sortedDistanceToArrivalLs, 5) + " / 10%="
				+ top(sortedDistanceToArrivalLs, 10) + " / med=" + top(sortedDistanceToArrivalLs, 50) + " / 90%=" + top(sortedDistanceToArrivalLs, 90) + " / 95%="
				+ top(sortedDistanceToArrivalLs, 95) + " / 99%=" + top(sortedDistanceToArrivalLs, 99) + " / max=" + max(sortedDistanceToArrivalLs));
		logger.info("semiMajorAxisAu:        min=" + min(sortedSemiMajorAxisAu) + " / 1%=" + top(sortedSemiMajorAxisAu, 1) + " / 5%=" + top(sortedSemiMajorAxisAu, 5) + " / 10%="
				+ top(sortedSemiMajorAxisAu, 10) + " / med=" + top(sortedSemiMajorAxisAu, 50) + " / 90%=" + top(sortedSemiMajorAxisAu, 90) + " / 95%=" + top(sortedSemiMajorAxisAu, 95)
				+ " / 99%=" + top(sortedSemiMajorAxisAu, 99) + " / max=" + max(sortedSemiMajorAxisAu));
		logger.info("earthMasses:            min=" + min(sortedEarthMasses) + " / 1%=" + top(sortedEarthMasses, 1) + " / 5%=" + top(sortedEarthMasses, 5) + " / 10%="
				+ top(sortedEarthMasses, 10) + " / med=" + top(sortedEarthMasses, 50) + " / 90%=" + top(sortedEarthMasses, 90) + " / 95%=" + top(sortedEarthMasses, 95) + " / 99%="
				+ top(sortedEarthMasses, 99) + " / max=" + max(sortedEarthMasses));
		logger.info("radiusKm:               min=" + min(sortedRadiusKm) + " / 1%=" + top(sortedRadiusKm, 1) + " / 5%=" + top(sortedRadiusKm, 5) + " / 10%=" + top(sortedRadiusKm, 10)
				+ " / med=" + top(sortedRadiusKm, 50) + " / 90%=" + top(sortedRadiusKm, 90) + " / 95%=" + top(sortedRadiusKm, 95) + " / 99%=" + top(sortedRadiusKm, 99) + " / max="
				+ max(sortedRadiusKm));
		logger.info("gravityG:               min=" + min(sortedGravityG) + " / 1%=" + top(sortedGravityG, 1) + " / 5%=" + top(sortedGravityG, 5) + " / 10%=" + top(sortedGravityG, 10)
				+ " / med=" + top(sortedGravityG, 50) + " / 90%=" + top(sortedGravityG, 90) + " / 95%=" + top(sortedGravityG, 95) + " / 99%=" + top(sortedGravityG, 99) + " / max="
				+ max(sortedGravityG));
	}

	private static BigDecimal min(List<BigDecimal> sortedValues) {
		return sortedValues.get(0);
	}

	private static BigDecimal max(List<BigDecimal> sortedValues) {
		return sortedValues.get(sortedValues.size() - 1);
	}

	private static BigDecimal top(List<BigDecimal> sortedValues, int percent) {
		int idx = (sortedValues.size() * percent) / 100;
		return sortedValues.get(idx);
	}

	static class BigDecimalFloatComparator implements Comparator<BigDecimal> {
		@Override
		public int compare(BigDecimal bd1, BigDecimal bd2) {
			return new Float(bd1.floatValue()).compareTo(new Float(bd2.floatValue()));
		}
	}

}
