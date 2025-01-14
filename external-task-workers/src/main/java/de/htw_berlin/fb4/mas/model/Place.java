package de.htw_berlin.fb4.mas.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Place {
    private Address address;
    private Geo geo;
    private ContainedInPlace containedInPlace;
}