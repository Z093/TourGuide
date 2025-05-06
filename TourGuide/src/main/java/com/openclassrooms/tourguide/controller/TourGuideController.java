package com.openclassrooms.tourguide.controller;

import com.openclassrooms.tourguide.dto.NearbyAttractionDTO;
import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;
import gpsUtil.location.Attraction;
import gpsUtil.location.VisitedLocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tripPricer.Provider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tourguide")
public class TourGuideController {

    private final TourGuideService tourGuideService;

    @Autowired
    public TourGuideController(TourGuideService tourGuideService) {
        this.tourGuideService = tourGuideService;
    }

    @GetMapping("/")
    public String index() {
        return "Bienvenue sur l'API TourGuide!";
    }

    @GetMapping("/getLocation")
    public ResponseEntity<Map<String, Object>> getLocation(@RequestParam String userName) {
        User user = tourGuideService.getUser(userName);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Utilisateur non trouvé"));
        }

        VisitedLocation visitedLocation = tourGuideService.getUserLocation(user);
        Map<String, Object> response = new HashMap<>();
        response.put("userId", visitedLocation.userId);
        response.put("latitude", visitedLocation.location.latitude);
        response.put("longitude", visitedLocation.location.longitude);
        response.put("timeVisited", visitedLocation.timeVisited);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/getNearbyAttractions")
    public ResponseEntity<List<NearbyAttractionDTO>> getNearbyAttractions(@RequestParam String userName) {
        User user = tourGuideService.getUser(userName);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        VisitedLocation visitedLocation = tourGuideService.getUserLocation(user);
        List<NearbyAttractionDTO> nearbyAttractions = tourGuideService.getFiveClosestAttractions(visitedLocation, user);

        return ResponseEntity.ok(nearbyAttractions);
    }

    @GetMapping("/getRewards")
    public ResponseEntity<List<UserReward>> getRewards(@RequestParam String userName) {
        User user = tourGuideService.getUser(userName);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        List<UserReward> rewards = tourGuideService.getUserRewards(user);
        return ResponseEntity.ok(rewards);
    }

    @GetMapping("/getAllAttractions")
    public ResponseEntity<List<Attraction>> getAllAttractions() {
        VisitedLocation visitedLocation = tourGuideService.getUserLocation(tourGuideService.getAllUsers().get(0));
        List<Attraction> attractions = tourGuideService.getNearByAttractions(visitedLocation);
        return ResponseEntity.ok(attractions);
    }

    @GetMapping("/getTripDeals")
    public ResponseEntity<List<Provider>> getTripDeals(@RequestParam String userName) {
        User user = tourGuideService.getUser(userName);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        List<Provider> tripDeals = tourGuideService.getTripDeals(user);
        return ResponseEntity.ok(tripDeals);
    }

    @GetMapping("/trackUser")
    public ResponseEntity<Map<String, Object>> trackUser(@RequestParam String userName) {
        User user = tourGuideService.getUser(userName);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Utilisateur non trouvé"));
        }

        VisitedLocation visitedLocation = tourGuideService.trackUserLocation(user);
        Map<String, Object> response = new HashMap<>();
        response.put("userId", visitedLocation.userId);
        response.put("latitude", visitedLocation.location.latitude);
        response.put("longitude", visitedLocation.location.longitude);
        response.put("timeVisited", visitedLocation.timeVisited);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/getAllUsers")
    public ResponseEntity<List<User>> getAllUsers() {
        List<User> users = tourGuideService.getAllUsers();
        return ResponseEntity.ok(users);
    }
}

