package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;

import tripPricer.Provider;
import tripPricer.TripPricer;

import com.openclassrooms.tourguide.dto.NearbyAttractionDTO;

@Service
public class TourGuideService {
	private Logger logger = LoggerFactory.getLogger(TourGuideService.class);
	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;
	boolean testMode = true;
	private ExecutorService executorService;

	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;

		// Initialisation du ExecutorService pour le traitement parallèle
		int processors = Runtime.getRuntime().availableProcessors();
		executorService = Executors.newFixedThreadPool(processors * 2);

		Locale.setDefault(Locale.US);

		if (testMode) {
			logger.info("TestMode enabled");
			logger.debug("Initializing users");
			initializeInternalUsers();
			logger.debug("Finished initializing users");
		}
		tracker = new Tracker(this);
		addShutDownHook();
	}

	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	public VisitedLocation getUserLocation(User user) {
		VisitedLocation visitedLocation = (user.getVisitedLocations().size() > 0) ? user.getLastVisitedLocation()
				: trackUserLocation(user);
		return visitedLocation;
	}

	public User getUser(String userName) {
		return internalUserMap.get(userName);
	}

	public List<User> getAllUsers() {
		return internalUserMap.values().stream().collect(Collectors.toList());
	}

	public void addUser(User user) {
		if (!internalUserMap.containsKey(user.getUserName())) {
			internalUserMap.put(user.getUserName(), user);
		}
	}

	public List<Provider> getTripDeals(User user) {
		int cumulatativeRewardPoints = user.getUserRewards().stream().mapToInt(i -> i.getRewardPoints()).sum();
		List<Provider> providers = tripPricer.getPrice(tripPricerApiKey, user.getUserId(),
				user.getUserPreferences().getNumberOfAdults(), user.getUserPreferences().getNumberOfChildren(),
				user.getUserPreferences().getTripDuration(), cumulatativeRewardPoints);
		user.setTripDeals(providers);
		return providers;
	}

	public VisitedLocation trackUserLocation(User user) {
		VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
		user.addToVisitedLocations(visitedLocation);
		rewardsService.calculateRewards(user);
		return visitedLocation;
	}

	/**
	 * Tracks user locations in parallel for a list of users
	 * @param users List of users to track
	 * @return List of VisitedLocation objects
	 */
	public List<VisitedLocation> trackUserLocationsInParallel(List<User> users) {
		try {
			List<CompletableFuture<VisitedLocation>> futures = users.stream()
					.map(user -> CompletableFuture.supplyAsync(() -> trackUserLocation(user), executorService))
					.collect(Collectors.toList());

			// Wait for all futures to complete and collect results
			List<VisitedLocation> visitedLocations = futures.stream()
					.map(CompletableFuture::join)
					.collect(Collectors.toList());

			return visitedLocations;
		} catch (Exception e) {
			logger.error("Error tracking user locations in parallel", e);
			throw e;
		}
	}

	public List<Attraction> getNearByAttractions(VisitedLocation visitedLocation) {
		List<Attraction> nearbyAttractions = new ArrayList<>();
		for (Attraction attraction : gpsUtil.getAttractions()) {
			if (rewardsService.isWithinAttractionProximity(attraction, visitedLocation.location)) {
				nearbyAttractions.add(attraction);
			}
		}

		return nearbyAttractions;
	}

	/**
	 * Get the five closest attractions to the user's location
	 * @param visitedLocation The user's current location
	 * @param user The user to calculate rewards for
	 * @return A list of at most five NearbyAttractionDTO objects
	 */
	public List<NearbyAttractionDTO> getFiveClosestAttractions(VisitedLocation visitedLocation, User user) {
		List<Attraction> attractions = gpsUtil.getAttractions();

		// Calculate distances and create a list of attractions with their distances
		List<Map.Entry<Attraction, Double>> attractionsWithDistances = new ArrayList<>();
		for (Attraction attraction : attractions) {
			double distance = rewardsService.getDistance(attraction, visitedLocation.location);
			attractionsWithDistances.add(Map.entry(attraction, distance));
		}

		// Sort by distance
		attractionsWithDistances.sort(Map.Entry.comparingByValue());

		// Take the 5 closest attractions
		List<NearbyAttractionDTO> closestAttractions = new ArrayList<>();
		for (int i = 0; i < Math.min(5, attractionsWithDistances.size()); i++) {
			Map.Entry<Attraction, Double> entry = attractionsWithDistances.get(i);
			Attraction attraction = entry.getKey();
			double distance = entry.getValue();
			int rewardPoints = rewardsService.getRewardPoints(attraction, user);

			NearbyAttractionDTO dto = new NearbyAttractionDTO(
					attraction.attractionName,
					attraction.latitude,
					attraction.longitude,
					visitedLocation.location.latitude,
					visitedLocation.location.longitude,
					distance,
					rewardPoints
			);

			closestAttractions.add(dto);
		}

		return closestAttractions;
	}

	public void shutdown() {
		if (executorService != null) {
			executorService.shutdown();
			try {
				if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
					executorService.shutdownNow();
				}
			} catch (InterruptedException e) {
				executorService.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
		tracker.stopTracking();
	}

	private void addShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				tracker.stopTracking();
				if (executorService != null) {
					executorService.shutdown();
				}
			}
		});
	}

	/**********************************************************************************
	 *
	 * Methods Below: For Internal Testing
	 *
	 **********************************************************************************/
	private static final String tripPricerApiKey = "test-server-api-key";
	// Database connection will be used for external users, but for testing purposes
	// internal users are provided and stored in memory
	private final Map<String, User> internalUserMap = new HashMap<>();

	private void initializeInternalUsers() {
		IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
			String userName = "internalUser" + i;
			String phone = "000";
			String email = userName + "@tourGuide.com";
			User user = new User(UUID.randomUUID(), userName, phone, email);
			generateUserLocationHistory(user);

			internalUserMap.put(userName, user);
		});
		logger.debug("Created " + InternalTestHelper.getInternalUserNumber() + " internal test users.");
	}

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
		});
	}

	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}
}