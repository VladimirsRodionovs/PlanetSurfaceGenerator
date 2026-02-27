CREATE TABLE IF NOT EXISTS PlanetsSurfaces2 (
  StarSys INT NOT NULL,
  PlanetIdx INT NOT NULL,
  PlanetName VARCHAR(128) NOT NULL,
  PlanetSeed BIGINT NOT NULL,
  HexDataBin LONGBLOB NOT NULL,
  HexDataUSize INT UNSIGNED NOT NULL,
  HexDataSizeEnc VARCHAR(16) NOT NULL DEFAULT 'gzip',
  UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CHECK (HexDataSizeEnc IN ('gzip')),
  PRIMARY KEY (StarSys, PlanetIdx),
  KEY idx_planet_seed (PlanetSeed),
  KEY idx_updated_at (UpdatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
