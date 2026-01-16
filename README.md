# Metabase Rill Driver

A custom Metabase driver that queries Rill APIs, enabling you to browse and query Rill Custom APIs directly from Metabase.

## Features

- Auto-discovers all Rill Custom APIs as "tables"
- Infers field schemas from JSON responses
- Executes queries against Rill API endpoints

## Prerequisites

1. **Clojure CLI** (version 1.10.3+)
   ```bash
   # macOS
   brew install clojure/tools/clojure

   # Linux
   curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
   chmod +x linux-install.sh
   sudo ./linux-install.sh
   ```

2. **Metabase Source** (for building)
   ```bash
   git clone https://github.com/metabase/metabase.git
   cd metabase
   ```

## Building the Driver

### Option 1: Using Metabase Build System

From the Metabase repository directory:

```bash
DRIVER_PATH=/path/to/rill-driver

clojure -Sdeps "{:aliases {:rill {:extra-deps {com.metabase/rill-driver {:local/root \"$DRIVER_PATH\"}}}}}" \
  -X:build:rill build-drivers.build-driver/build-driver! \
  :driver :rill \
  :project-dir "\"$DRIVER_PATH\"" \
  :target-dir "\"$DRIVER_PATH/target\""
```

### Option 2: Manual JAR Build

```bash
cd rill-driver

# Create an uberjar
clojure -X:deps prep
clojure -M -e "(compile 'metabase.driver.rill)"

# Package as JAR (requires additional build configuration)
```

## Installation

1. Build the driver JAR (see above)
2. Copy the resulting `rill.metabase-driver.jar` to your Metabase `plugins/` directory:
   ```bash
   cp target/rill.metabase-driver.jar /path/to/metabase/plugins/
   ```
3. Restart Metabase

## Configuration

When adding a new database connection in Metabase:

1. Select **Rill** as the database type
2. Configure the connection:
   - **Host URL**: Base URL of your Rill server (e.g., `http://localhost:9009`)
   - **Instance**: Rill instance name (default: `default`)

## Usage

Once connected:

1. **Browse Tables**: Each Rill Custom API appears as a table in the Metabase data browser
2. **Query Data**: Use Metabase's query builder or write native queries
3. **Create Visualizations**: Build charts and dashboards from your Rill API data

## How It Works

1. **Table Discovery**: The driver queries `/v1/instances/{instance}/resources` and filters for `rill.runtime.v1.API` resources
2. **Schema Inference**: When describing a table, the driver fetches sample data from the API and infers column types from the JSON response
3. **Query Execution**: Queries are translated to HTTP GET requests against `/v1/instances/{instance}/api/{api_name}`

## Type Mapping

| JSON Type | Metabase Type |
|-----------|---------------|
| string    | Text          |
| integer   | Integer       |
| float     | Float         |
| boolean   | Boolean       |
| null      | Text          |

## Limitations (v1.0)

- **No Authentication**: Currently does not support authenticated Rill instances
- **No Query Parameters**: Returns the full API response without filtering
- **No Aggregations**: Basic table browsing only, no server-side aggregations
- **Schema from First Row**: Field types are inferred from the first row, which may miss fields that are null in that row

## Troubleshooting

### Driver not appearing in Metabase
- Ensure the JAR is in the correct `plugins/` directory
- Check Metabase logs for plugin loading errors
- Verify the JAR file permissions

### Connection test fails
- Verify the Rill server is running and accessible
- Check the host URL format (should include protocol, e.g., `http://`)
- Ensure the instance name is correct

### No tables discovered
- Verify you have Custom APIs defined in your Rill project
- Check that the APIs are deployed and accessible

## Development

### Project Structure

```
rill-driver/
├── deps.edn                           # Clojure dependencies
├── resources/
│   └── metabase-plugin.yaml           # Plugin manifest
└── src/
    └── metabase/
        └── driver/
            └── rill.clj               # Main driver implementation
```

### Running Tests

```bash
# From the rill-driver directory
clojure -M:test
```

## References

- [Metabase Driver Development Guide](https://www.metabase.com/docs/latest/developers-guide/drivers/start)
- [Rill Custom APIs Documentation](https://docs.rilldata.com/build/custom-apis)
- [Sudoku Sample Driver](https://github.com/metabase/sudoku-driver)
- [HTTP Driver Example](https://github.com/tlrobinson/metabase-http-driver)

## License

This driver is provided as-is for integration purposes.
