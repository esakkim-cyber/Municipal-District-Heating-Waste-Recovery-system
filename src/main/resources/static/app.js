const datasetSelect = document.querySelector("#dataset-select");
const fieldSelect = document.querySelector("#field-select");
const canvas = document.querySelector("#series-chart");
const context = canvas.getContext("2d");
const tooltip = document.querySelector("#chart-tooltip");
const chartError = document.querySelector("#chart-error");
const navItems = [...document.querySelectorAll(".nav-item")];
let datasets = [];
let visibleDatasets = [];
let currentSeries = [];

const numberFormat = new Intl.NumberFormat("en", { maximumFractionDigits: 2 });
const integerFormat = new Intl.NumberFormat("en");

async function getJson(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`Request failed (${response.status})`);
    }
    return response.json();
}

async function initialize() {
    try {
        const [overview, sources] = await Promise.all([getJson("/api/overview"), getJson("/api/datasets")]);
        datasets = sources;
        document.querySelector("#metric-records").textContent = integerFormat.format(overview.totalRecords);
        document.querySelector("#metric-sources").textContent = String(overview.datasets).padStart(2, "0");
        document.querySelector("#metric-building").textContent = integerFormat.format(overview.buildingRecords);
        document.querySelector("#metric-district").textContent = integerFormat.format(overview.districtRecords);
        updateDatasetOptions("All");
        await loadSeries();
    } catch (error) {
        showError(`The dashboard could not load its project data. ${error.message}`);
    }
}

function updateDatasetOptions(category) {
    visibleDatasets = category === "All" ? datasets : datasets.filter((item) => item.category === category);
    datasetSelect.replaceChildren(...visibleDatasets.map((item) => {
        const option = document.createElement("option");
        option.value = item.id;
        option.textContent = `${item.category} / ${item.name}`;
        return option;
    }));
    document.querySelector("#visible-count").textContent = `${visibleDatasets.length} source${visibleDatasets.length === 1 ? "" : "s"}`;
}

function updateFieldOptions(dataset) {
    fieldSelect.replaceChildren(...dataset.fields.map((field) => {
        const option = document.createElement("option");
        option.value = field;
        option.textContent = field;
        return option;
    }));
    if (dataset.fields.includes(dataset.defaultField)) {
        fieldSelect.value = dataset.defaultField;
    }
}

async function loadSeries() {
    const dataset = datasets.find((item) => item.id === datasetSelect.value);
    if (!dataset) return;
    updateFieldOptions(dataset);
    chartError.hidden = true;
    tooltip.style.display = "none";

    try {
        const query = new URLSearchParams({ field: fieldSelect.value, limit: "260" });
        const series = await getJson(`/api/datasets/${encodeURIComponent(dataset.id)}/series?${query}`);
        currentSeries = series.points;
        document.querySelector("#chart-title").textContent = `${dataset.name} / ${series.field}`;
        document.querySelector("#chart-description").textContent = dataset.description;
        document.querySelector("#chart-legend-label").textContent = `${dataset.category.toUpperCase()} SERIES`;
        document.querySelector("#dataset-description").textContent = dataset.description;
        document.querySelector("#dataset-category").textContent = dataset.category;
        document.querySelector("#dataset-unit").textContent = series.unit;
        document.querySelector("#dataset-rows").textContent = `${integerFormat.format(dataset.records)} records`;
        document.querySelector("#table-field-heading").textContent = series.field;
        document.querySelector("#chart-range").textContent = currentSeries.length
            ? `${numberFormat.format(currentSeries[0].time)} — ${numberFormat.format(currentSeries.at(-1).time)}` : "No records";
        document.querySelector("#chart-points").textContent = integerFormat.format(currentSeries.length);
        document.querySelector("#download-link").href = `/api/datasets/${encodeURIComponent(dataset.id)}/csv`;
        updateSampleTable(series);
        drawChart();
    } catch (error) {
        showError(`This series could not be loaded. ${error.message}`);
    }
}

function updateSampleTable(series) {
    const body = document.querySelector("#sample-rows");
    body.replaceChildren(...series.points.slice(0, 6).map((point) => {
        const row = document.createElement("tr");
        const time = document.createElement("td");
        const value = document.createElement("td");
        time.textContent = numberFormat.format(point.time);
        value.textContent = numberFormat.format(point.value);
        row.append(time, value);
        return row;
    }));
}

function drawChart() {
    const bounds = canvas.getBoundingClientRect();
    if (!bounds.width || !bounds.height || !currentSeries.length) return;
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.round(bounds.width * ratio);
    canvas.height = Math.round(bounds.height * ratio);
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, bounds.width, bounds.height);

    const padding = { top: 20, right: 20, bottom: 35, left: 61 };
    const plotWidth = bounds.width - padding.left - padding.right;
    const plotHeight = bounds.height - padding.top - padding.bottom;
    const values = currentSeries.map((point) => point.value);
    let minimum = Math.min(...values);
    let maximum = Math.max(...values);
    if (minimum === maximum) {
        minimum -= Math.abs(minimum || 1) * 0.08;
        maximum += Math.abs(maximum || 1) * 0.08;
    } else {
        const extra = (maximum - minimum) * 0.1;
        minimum -= extra;
        maximum += extra;
    }
    const firstTime = currentSeries[0].time;
    const lastTime = currentSeries.at(-1).time;
    const xAt = (index) => padding.left + (currentSeries.length === 1 ? 0 : index / (currentSeries.length - 1)) * plotWidth;
    const yAt = (value) => padding.top + (maximum - value) / (maximum - minimum) * plotHeight;

    context.font = "10px Segoe UI, sans-serif";
    context.textBaseline = "middle";
    for (let step = 0; step <= 4; step++) {
        const y = padding.top + plotHeight * step / 4;
        const value = maximum - (maximum - minimum) * step / 4;
        context.beginPath();
        context.strokeStyle = "#e8ede8";
        context.lineWidth = 1;
        context.moveTo(padding.left, y);
        context.lineTo(bounds.width - padding.right, y);
        context.stroke();
        context.fillStyle = "#89958e";
        context.textAlign = "right";
        context.fillText(numberFormat.format(value), padding.left - 10, y);
    }

    const xLabels = [firstTime, currentSeries[Math.floor((currentSeries.length - 1) / 2)].time, lastTime];
    xLabels.forEach((value, index) => {
        const x = padding.left + plotWidth * index / 2;
        context.fillStyle = "#89958e";
        context.textAlign = index === 0 ? "left" : index === 2 ? "right" : "center";
        context.fillText(numberFormat.format(value), x, bounds.height - 15);
    });

    context.beginPath();
    currentSeries.forEach((point, index) => {
        const x = xAt(index);
        const y = yAt(point.value);
        if (index === 0) context.moveTo(x, y);
        else context.lineTo(x, y);
    });
    context.lineTo(xAt(currentSeries.length - 1), padding.top + plotHeight);
    context.lineTo(xAt(0), padding.top + plotHeight);
    context.closePath();
    const area = context.createLinearGradient(0, padding.top, 0, padding.top + plotHeight);
    area.addColorStop(0, "rgba(79, 133, 99, 0.18)");
    area.addColorStop(1, "rgba(79, 133, 99, 0.015)");
    context.fillStyle = area;
    context.fill();

    context.beginPath();
    currentSeries.forEach((point, index) => {
        if (index === 0) context.moveTo(xAt(index), yAt(point.value));
        else context.lineTo(xAt(index), yAt(point.value));
    });
    context.strokeStyle = "#427458";
    context.lineWidth = 2;
    context.lineJoin = "round";
    context.lineCap = "round";
    context.stroke();
}

function showError(message) {
    chartError.textContent = message;
    chartError.hidden = false;
    document.querySelector("#chart-title").textContent = "Data unavailable";
    document.querySelector("#chart-description").textContent = "Check that the project CSV files are present.";
}

datasetSelect.addEventListener("change", loadSeries);
fieldSelect.addEventListener("change", loadSeries);
navItems.forEach((button) => button.addEventListener("click", async () => {
    navItems.forEach((item) => item.classList.toggle("active", item === button));
    updateDatasetOptions(button.dataset.category);
    await loadSeries();
    document.querySelector("#explorer").scrollIntoView({ behavior: "smooth", block: "start" });
}));
document.querySelectorAll("[data-jump-category]").forEach((link) => link.addEventListener("click", () => {
    const category = link.dataset.jumpCategory;
    const matchingNav = navItems.find((item) => item.dataset.category === category);
    matchingNav?.click();
}));
canvas.addEventListener("pointermove", (event) => {
    if (!currentSeries.length) return;
    const rect = canvas.getBoundingClientRect();
    const plotLeft = 61;
    const plotRight = rect.width - 20;
    const relativeX = Math.max(0, Math.min(1, (event.clientX - rect.left - plotLeft) / (plotRight - plotLeft)));
    const index = Math.round(relativeX * (currentSeries.length - 1));
    const point = currentSeries[index];
    tooltip.textContent = `${numberFormat.format(point.time)} s  ·  ${numberFormat.format(point.value)}`;
    tooltip.style.display = "block";
    tooltip.style.left = `${Math.min(rect.width - 150, Math.max(8, event.clientX - rect.left + 12))}px`;
    tooltip.style.top = `${Math.max(5, event.clientY - rect.top - 32)}px`;
});
canvas.addEventListener("pointerleave", () => { tooltip.style.display = "none"; });
window.addEventListener("resize", drawChart);
initialize();