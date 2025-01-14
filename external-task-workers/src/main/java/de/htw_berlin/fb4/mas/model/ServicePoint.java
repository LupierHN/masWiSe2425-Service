package de.htw_berlin.fb4.mas.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ServicePoint {
    private String url;
    private Location location;
    private String name;
    private int distance;
    private Place place;
    private List<OpeningHours> openingHours;
    private List<ClosurePeriod> closurePeriods;
    private List<String> serviceTypes;
    private String availableCapacity;
    private List<AverageCapacityDayOfWeek> averageCapacityDayOfWeek;
}