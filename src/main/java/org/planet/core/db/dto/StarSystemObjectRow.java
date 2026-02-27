package org.planet.core.db.dto;

public class StarSystemObjectRow {
    public int starSysIdx;          // X
    public int objectInternalId;    // ObjectInternalID
    public int objectType;          // ObjectType (3 = moon)
    public int objectPlanetType;    // ObjectPlanetType
    public int objectOrbitHost;     // ObjectOrbitHost (parent ObjectInternalID)
    public double orbitSemimajorAxisAU; // ObjectOrbitSemimajorAxisAU
    public double orbitInclinationDeg; // ObjectOrbitInclination
    public double objectMassEarth;  // optional mass in Earth masses (if present in table)
    public String objectName;       // ObjectName
    public String objectDescription; // ObjectDescription (CSV blob)
    public double orbitMeanMotionPerDay; // ObjectOrbitMeanMotionPerDay (deg/day)
    public double axialTiltDeg;          // ObjectRotationInclination (deg)
    public double rotationPeriodHours;  // ObjectRotationSpeedSideric (hours)
    public double rotationSpeed;        // ObjectRotationSpeed
    public int rotationPrograde;        // ObjectProGrade (1 prograde, 0 retrograde)

    // сюда позже добавим реальные параметры (gravity/temp/atm/...)
}
