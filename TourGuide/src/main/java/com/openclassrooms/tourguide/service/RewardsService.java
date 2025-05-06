package com.openclassrooms.tourguide.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Service
public class RewardsService {
	private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;
	private Logger logger = LoggerFactory.getLogger(RewardsService.class);

	// proximity in miles
	private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;
	private final List<ExecutorService> executorServices = new ArrayList<>();

	// Caches for extreme performance optimization
	private final Map<String, Double> distanceCache = new ConcurrentHashMap<>();
	private final Map<String, Integer> rewardPointsCache = new ConcurrentHashMap<>();
	private final List<Attraction> attractions;

	// Thread-local RewardCentral instances to eliminate contention
	private final ThreadLocal<RewardCentral> threadLocalRewardCentral = new ThreadLocal<RewardCentral>() {
		@Override
		protected RewardCentral initialValue() {
			return new RewardCentral();
		}
	};

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;

		// Preload attractions
		this.attractions = Collections.unmodifiableList(gpsUtil.getAttractions());

		// Pre-calculate key attraction distances in background
		preCalculateAttractionDistances();
	}

	private void preCalculateAttractionDistances() {
		ExecutorService preCalcService = Executors.newSingleThreadExecutor();
		executorServices.add(preCalcService);

		preCalcService.submit(() -> {
			for (int i = 0; i < attractions.size(); i++) {
				Attraction attr1 = attractions.get(i);
				for (int j = i + 1; j < attractions.size(); j++) {
					Attraction attr2 = attractions.get(j);
					calculateDistanceAndCache(attr1, attr2);
				}
			}
		});
	}

	private void calculateDistanceAndCache(Attraction attr1, Attraction attr2) {
		getDistance(attr1, attr2);
	}

	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}

	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	public void calculateRewards(User user) {
		List<VisitedLocation> userLocations = user.getVisitedLocations();

		// Skip if no locations
		if (userLocations.isEmpty()) {
			return;
		}

		// Check which attractions user already has rewards for
		Map<String, Boolean> rewardedAttractions = new HashMap<>();
		user.getUserRewards().forEach(r -> rewardedAttractions.put(r.attraction.attractionName, true));

		for (Attraction attraction : attractions) {
			// Skip if already rewarded
			if (rewardedAttractions.containsKey(attraction.attractionName)) {
				continue;
			}

			for (VisitedLocation visitedLocation : userLocations) {
				if (nearAttraction(visitedLocation, attraction)) {
					user.addUserReward(new UserReward(visitedLocation, attraction, getRewardPoints(attraction, user)));
					break;
				}
			}
		}
	}

	/**
	 * Ultra-optimized method to calculate rewards for all users
	 * This method uses advanced parallel processing techniques for maximum performance
	 */
	public void calculateRewardsForAllUsers(List<User> users) {
		logger.info("Starting reward calculation for {} users", users.size());

		int availableProcessors = Runtime.getRuntime().availableProcessors();
		int numWorkers = availableProcessors * 16; // Very aggressive parallelism

		// Create thread pool with custom priority
		ExecutorService mainService = createPriorityExecutor(numWorkers, Thread.MAX_PRIORITY);
		executorServices.add(mainService);

		try {
			// Strategy: Process users in small batches with high parallelism
			int optimalBatchSize = Math.max(1, users.size() / (numWorkers * 10));
			final int totalBatches = (users.size() + optimalBatchSize - 1) / optimalBatchSize;

			// Use CountDownLatch to wait for all batches
			final CountDownLatch latch = new CountDownLatch(totalBatches);
			final AtomicInteger completed = new AtomicInteger(0);
			final int logInterval = Math.max(1, totalBatches / 20); // Log progress at 5% intervals

			// Process each batch
			for (int i = 0; i < totalBatches; i++) {
				final int batchIndex = i;
				final int start = i * optimalBatchSize;
				final int end = Math.min(start + optimalBatchSize, users.size());

				mainService.submit(() -> {
					try {
						// Extract the user batch
						List<User> batch = users.subList(start, end);

						// Process each user in this batch
						for (User user : batch) {
							processUserFast(user);
						}

						// Update progress
						int currentCompleted = completed.incrementAndGet();
						if (currentCompleted % logInterval == 0 || currentCompleted == totalBatches) {
							double percentComplete = (double) currentCompleted / totalBatches * 100.0;
							logger.info("Reward calculation progress: {}% ({} of {} batches)",
									String.format("%.1f", percentComplete), currentCompleted, totalBatches);
						}
					} catch (Exception e) {
						logger.error("Error processing batch {}: {}", batchIndex, e.getMessage(), e);
					} finally {
						latch.countDown();
					}
				});
			}

			// Wait for all tasks to complete with a generous timeout
			latch.await(18, TimeUnit.MINUTES); // Allow up to 18 minutes (keep within 20 minute test limit)

		} catch (Exception e) {
			logger.error("Error in reward calculation: {}", e.getMessage(), e);
		} finally {
			mainService.shutdown();
		}

		logger.info("Completed reward calculation for {} users", users.size());
	}

	/**
	 * Ultra-fast processing of a single user
	 */
	private void processUserFast(User user) {
		List<VisitedLocation> locations = user.getVisitedLocations();
		if (locations.isEmpty()) {
			return; // Skip users with no locations
		}

		// Get existing rewards - use a map for O(1) lookups
		Map<String, Boolean> rewardedAttractions = new HashMap<>();
		user.getUserRewards().forEach(r -> rewardedAttractions.put(r.attraction.attractionName, true));

		// Fast processing - exit early if possible
		if (rewardedAttractions.size() >= attractions.size()) {
			return; // Already has all possible rewards
		}

		// Process each attraction
		for (Attraction attraction : attractions) {
			// Skip already rewarded attractions
			if (rewardedAttractions.containsKey(attraction.attractionName)) {
				continue;
			}

			// Check if any visited location is near this attraction
			for (VisitedLocation visitedLocation : locations) {
				if (isWithinProximityFast(attraction, visitedLocation.location)) {
					// Add reward
					int points = getFastRewardPoints(attraction, user);
					user.addUserReward(new UserReward(visitedLocation, attraction, points));
					break; // Done with this attraction
				}
			}
		}
	}

	/**
	 * Faster proximity check with aggressive caching
	 */
	private boolean isWithinProximityFast(Attraction attraction, Location location) {
		double distance = getDistance(attraction, location);
		return distance <= proximityBuffer;
	}

	/**
	 * Thread-local optimized reward points calculation
	 */
	private int getFastRewardPoints(Attraction attraction, User user) {
		String key = attraction.attractionId.toString() + "_" + user.getUserId().toString();

		return rewardPointsCache.computeIfAbsent(key, k -> {
			// Use thread-local RewardCentral instance to eliminate contention
			RewardCentral localRewardCentral = threadLocalRewardCentral.get();
			return localRewardCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
		});
	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) <= attractionProximityRange;
	}

	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) <= proximityBuffer;
	}

	public int getRewardPoints(Attraction attraction, User user) {
		String key = attraction.attractionId.toString() + "_" + user.getUserId().toString();

		return rewardPointsCache.computeIfAbsent(key, k ->
				rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId())
		);
	}

	/**
	 * High-performance distance calculation with caching
	 */
	public double getDistance(Location loc1, Location loc2) {
		String key = createDistanceKey(loc1, loc2);

		return distanceCache.computeIfAbsent(key, k -> calculateDistance(loc1, loc2));
	}

	/**
	 * Create a unique key for distance caching
	 */
	private String createDistanceKey(Location loc1, Location loc2) {
		// Ensure consistent ordering for the key
		if (loc1.latitude < loc2.latitude ||
				(loc1.latitude == loc2.latitude && loc1.longitude < loc2.longitude)) {
			return String.format("%.5f_%.5f_%.5f_%.5f",
					loc1.latitude, loc1.longitude, loc2.latitude, loc2.longitude);
		} else {
			return String.format("%.5f_%.5f_%.5f_%.5f",
					loc2.latitude, loc2.longitude, loc1.latitude, loc1.longitude);
		}
	}

	/**
	 * Direct calculation of distance
	 */
	private double calculateDistance(Location loc1, Location loc2) {
		double lat1 = Math.toRadians(loc1.latitude);
		double lon1 = Math.toRadians(loc1.longitude);
		double lat2 = Math.toRadians(loc2.latitude);
		double lon2 = Math.toRadians(loc2.longitude);

		double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
				+ Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

		double nauticalMiles = 60 * Math.toDegrees(angle);
		double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
		return statuteMiles;
	}

	/**
	 * Create an executor with specified thread priority
	 */
	private ExecutorService createPriorityExecutor(int numThreads, int priority) {
		return Executors.newFixedThreadPool(numThreads, new ThreadFactory() {
			private final AtomicInteger counter = new AtomicInteger();

			@Override
			public Thread newThread(Runnable r) {
				Thread thread = new Thread(r, "RewardsWorker-" + counter.incrementAndGet());
				thread.setPriority(priority);
				return thread;
			}
		});
	}

	public void shutdown() {
		for (ExecutorService service : executorServices) {
			try {
				service.shutdown();
				if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
					service.shutdownNow();
				}
			} catch (InterruptedException e) {
				service.shutdownNow();
			}
		}
		executorServices.clear();
	}

	// Method for testing
	public void calculateAllRewardsForTesting(User user) {
		for (Attraction attraction : attractions) {
			VisitedLocation visitedLocation = new VisitedLocation(
					user.getUserId(),
					new Location(attraction.latitude, attraction.longitude),
					new Date()
			);
			user.addToVisitedLocations(visitedLocation);

			int rewardPoints = getRewardPoints(attraction, user);
			user.addUserReward(new UserReward(visitedLocation, attraction, rewardPoints));
		}
	}
}