// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.mapwithai.backend;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.stream.JsonParser;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.io.CachedFile;
import org.openstreetmap.josm.plugins.mapwithai.MapWithAIPlugin;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Territories;

public final class MapWithAIAvailability extends DataAvailability {
    private static String rapidReleases = "https://raw.githubusercontent.com/facebookmicrosites/Open-Mapping-At-Facebook/master/data/rapid_releases.geojson";
    /** Original country, replacement countries */
    private static final Map<String, Collection<String>> COUNTRY_NAME_FIX = new HashMap<>();

    static {
        COUNTRY_NAME_FIX.put("Egypt", Collections.singleton("Egypt, Arab Rep."));
        COUNTRY_NAME_FIX.put("Dem. Rep. Congo", Collections.singleton("Congo, Dem. Rep."));
        COUNTRY_NAME_FIX.put("Democratic Republic of the Congo", Collections.singleton("Congo, Dem. Rep."));
        COUNTRY_NAME_FIX.put("eSwatini", Collections.singleton("Swaziland"));
        COUNTRY_NAME_FIX.put("Gambia", Collections.singleton("Gambia, The"));
        COUNTRY_NAME_FIX.put("The Bahamas", Collections.singleton("Bahamas, The"));
        COUNTRY_NAME_FIX.put("Ivory Coast", Collections.singleton("Côte d'Ivoire"));
        COUNTRY_NAME_FIX.put("Somaliland", Collections.singleton("Somalia")); // Technically a self-declared independent
        // area of Somalia
        COUNTRY_NAME_FIX.put("Carribean Countries",
                Arrays.asList("Antigua and Barbuda", "Anguilla", "Barbados", "British Virgin Islands", "Cayman Islands",
                        "Dominica", "Dominican Republic", "Grenada", "Guadeloupe", "Haiti", "Jamaica", "Martinique",
                        "Montserrat", "Puerto Rico", "Saba", "Saint-Barthélemy", "Saint-Martin", "Sint Eustatius",
                        "Sint Maarten", "St. Kitts and Nevis", "St. Lucia", "St. Vincent and the Grenadines",
                        "Turks and Caicos Islands"));
        COUNTRY_NAME_FIX.put("Falkland Islands (Islas Maldivas)", Collections.singleton("Falkland Islands"));
        COUNTRY_NAME_FIX.put("Laos", Collections.singleton("Lao PDR"));
        COUNTRY_NAME_FIX.put("East Timor", Collections.singleton("Timor-Leste"));
        COUNTRY_NAME_FIX.put("Congo", Collections.singleton("Congo, Rep."));
        COUNTRY_NAME_FIX.put("North Macedonia", Collections.singleton("Macedonia, FYR"));
        COUNTRY_NAME_FIX.put("Venezuela", Collections.singleton("Venezuela, RB"));
        POSSIBLE_DATA_POINTS.put("highway", "RapiD roads available");
        POSSIBLE_DATA_POINTS.put("building", "MS buildings available");
    }

    public MapWithAIAvailability() {
        super();
        try (CachedFile cachedRapidReleases = new CachedFile(rapidReleases);
                JsonParser parser = Json.createParser(cachedRapidReleases.getContentReader())) {
            cachedRapidReleases.setMaxAge(604_800);
            parser.next();
            final Stream<Entry<String, JsonValue>> entries = parser.getObjectStream();
            final Optional<Entry<String, JsonValue>> objects = entries.filter(entry -> "objects".equals(entry.getKey()))
                    .findFirst();
            if (objects.isPresent()) {
                final JsonObject value = objects.get().getValue().asJsonObject();
                if (value != null) {
                    final JsonObject centroid = value.getJsonObject("rapid_releases_points");
                    if (centroid != null) {
                        final JsonArray countries = centroid.getJsonArray("geometries");
                        if (countries != null) {
                            COUNTRIES.clear();
                            COUNTRIES.putAll(parseForCountries(countries));
                        }
                    }
                }
            }
        } catch (IOException e) {
            Logging.debug(e);
        }
    }

    private static Map<String, Map<String, Boolean>> parseForCountries(JsonArray countries) {
        final Map<String, Map<String, Boolean>> returnCountries = new TreeMap<>();
        Territories.initialize();
        final DataSet territories = Territories.getDataSet();
        for (int i = 0; i < countries.size(); i++) {
            final JsonObject country = countries.getJsonObject(i).getJsonObject("properties");
            for (String countryName : cornerCaseNames(country.getString("Country"))) {
                final Optional<OsmPrimitive> realCountry = territories.allPrimitives().parallelStream()
                        .filter(primitive -> countryName.equalsIgnoreCase(primitive.get("name:en"))).findFirst();
                if (realCountry.isPresent()) {
                    final OsmPrimitive countryLoop = realCountry.get();
                    final String key = Optional.ofNullable(countryLoop.get("ISO3166-1:alpha2"))
                            .orElse(Optional.ofNullable(countryLoop.get("ISO3166-2")).orElse(""));
                    if ("".equals(key)) {
                        Logging.error("{0}: {1} does not have a \"ISO3166-1:alpha2\" or \"ISO3166-2\" key. {2}",
                                MapWithAIPlugin.NAME, countryLoop, countryLoop.getInterestingTags());
                    } else {
                        Logging.trace("{0}: {1} has a country code of {2}", MapWithAIPlugin.NAME,
                                countryLoop.get("name:en"), key);
                    }
                    // We need to handle cases like Alaska more elegantly
                    final Map<String, Boolean> data = returnCountries.getOrDefault(key, new TreeMap<>());
                    for (final Entry<String, String> entry : POSSIBLE_DATA_POINTS.entrySet()) {
                        final boolean hasData = "yes".equals(country.getString(entry.getValue()));
                        if (hasData || !data.containsKey(entry.getKey())) {
                            data.put(entry.getKey(), hasData);
                        }
                    }
                    returnCountries.put(key, data);
                } else {
                    Logging.error(tr("{0}: We couldn''t find {1}", MapWithAIPlugin.NAME, countryName));
                }
            }
        }
        return returnCountries;
    }

    private static Collection<String> cornerCaseNames(String name) {
        return COUNTRY_NAME_FIX.containsKey(name) ? COUNTRY_NAME_FIX.get(name) : Collections.singleton(name);
    }

    /**
     * @param url The URL where the MapWithAI data releases are.
     */
    public static void setReleaseUrl(String url) {
        rapidReleases = url;
    }

    @Override
    public String getUrl() {
        return MapWithAIPreferenceHelper.DEFAULT_MAPWITHAI_API;
    }
}
