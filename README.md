# TourGuide Project

TourGuide is a Java Spring Boot-based geolocation rewards system. It simulates a tourism application capable of tracking users, recommending attractions, and calculating reward points based on visited locations.

## Technologies

- Java 17
- Spring Boot 3.x
- Maven
- GitHub Actions (CI)
- Custom libraries: `gpsUtil`, `TripPricer`, `RewardCentral` (included as `.jar` files)

## Features

-  Track user locations
-  Recommend nearby attractions
-  Calculate user reward points based on proximity
-  Load tests with up to 100,000 simulated users
-  Highly optimized for parallel performance testing

## Installation

1. Clone this repository:
   ```bash
   git clone https://github.com/your-org/TourGuide.git
   cd TourGuide
   ```

2. Make sure the `.jar` dependencies exist under:
   ```
   TourGuide/libs/
     ├── gpsUtil.jar
     ├── TripPricer.jar
     └── RewardCentral.jar
   ```

3. Build the project:
   ```bash
   mvn clean install
   ```

##  Running Tests

### Unit Tests (default):
```bash
mvn test -Dtest=TestTourGuideService,TestRewardsService
```

### Performance Tests:
```bash
mvn test -Dtest=TestPerformance#highVolumeTrackLocation -Duser.count=10000
mvn test -Dtest=TestPerformance#highVolumeGetRewards -Duser.count=10000
```

Adjust the `user.count` to simulate different load levels.

##  Continuous Integration

This project includes a GitHub Actions pipeline:
- Runs on `push` and `pull_request` to `master`
- Runs unit tests
- Runs performance tests separately (100, 1000, 10000, 100000 users)
- Builds artifacts

Workflow file: `.github/workflows/maven.yml`

