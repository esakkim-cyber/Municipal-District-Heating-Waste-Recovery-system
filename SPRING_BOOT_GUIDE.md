# Thermal Atlas

Thermal Atlas is a Spring Boot website for browsing the building-heating and district-heating CSV datasets in this repository. It provides a responsive dashboard, summaries, interactive time-series charts, sample records, CSV downloads, and a small JSON API.

The original Python and Modelica optimization studies are preserved. This site visualizes their supplied data; it does not execute or reproduce the optimization solver.

## Run locally

Requirements: JDK 17 or newer and Maven 3.9 or newer.

From the repository root, run:

```powershell
mvn spring-boot:run
```

Open <http://localhost:8080> in a browser. Keep the repository root as the working directory so the app can read the original CSV folders.

To point the app at a copy of the data in a different location:

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--heating.data-root=C:/path/to/data"
```

## API

- `GET /api/overview` returns record totals.
- `GET /api/datasets` lists available datasets and numeric fields.
- `GET /api/datasets/{id}/series?field=qload&limit=240` returns a downsampled time series.
- `GET /api/datasets/{id}/csv` downloads the original CSV.

The dashboard uses the same-origin API. Dataset IDs are `building-measurements`, `building-forecast`, `building-price`, `building-reference`, `building-gains`, `building-ventilation`, `district-mpc`, and `district-optimization`.