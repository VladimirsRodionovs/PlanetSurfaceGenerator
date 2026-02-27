Its an instrument for game development.
Simulation of planetary formation from protoplanetary meterial to stable planet.
Stages, simplified:
0. Read Planet or Moon data from DB
1. Tecktonic plates.
2. Tecktonic stress to form mountains.
3. Height map.
4. Water map if water present.
5. Atmospheric simulation for high/mid/low seazon depending on planet orientation, if atmosphere present.
6. Surface impacts in atmosphere not present.
7. Biomes calculations if live present and temperature windoq is good.
8. Rivers generation if water and atmosphere present.
9. Resources generation taking in account live, tecktonics, river basins, water bodies.
10. Put result to DB.

    Simulations processing tiles with lon\log coordinates. Geometry - subdevided icosaendron. Suport different levels of subdiv.
