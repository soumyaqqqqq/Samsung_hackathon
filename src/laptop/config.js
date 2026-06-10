// FRIDAY configuration and endpoint definitions
globalThis.FRIDAY_CONFIG = {
    // Base host configuration
    BACKEND_HOST: "localhost:8000",  // Must match uvicorn --port

    // API Endpoints
    get wsUrl() {
        return `ws://${this.BACKEND_HOST}/ws/laptop`;
    },

    get telemetryUrl() {
        return `http://${this.BACKEND_HOST}/api/telemetry`;
    }
};
