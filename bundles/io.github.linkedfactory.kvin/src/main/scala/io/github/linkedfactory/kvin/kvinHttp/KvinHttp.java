package io.github.linkedfactory.kvin.kvinHttp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.linkedfactory.kvin.Kvin;
import io.github.linkedfactory.kvin.KvinListener;
import io.github.linkedfactory.kvin.KvinTuple;
import io.github.linkedfactory.kvin.Record;
import net.enilink.commons.iterator.*;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.util.*;

public class KvinHttp implements Kvin {

    String hostEndpoint;
    ArrayList<KvinListener> listeners = new ArrayList<>();
    ObjectMapper mapper = new ObjectMapper();
    CloseableHttpClient httpClient;

    public KvinHttp(String hostEndpoint) {
        this.hostEndpoint = hostEndpoint;
        this.httpClient = getHttpClient();
    }

    public CloseableHttpClient getHttpClient() {
        return HttpClients.createDefault();
    }

    @Override
    public boolean addListener(KvinListener listener) {
        try {
            listeners.add(listener);
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean removeListener(KvinListener listener) {
        try {
            listeners.remove(listener);
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void put(KvinTuple... tuples) {
        try {
            this.put(Arrays.asList(tuples));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void put(Iterable<KvinTuple> tuples) {
        try {
            // grouping
            Map<URI, Map<URI, List<KvinTuple>>> groupedData = new HashMap<>();
            for (KvinTuple tuple : tuples) {
                Map<URI, List<KvinTuple>> propertyData = groupedData.computeIfAbsent(tuple.item, (item) -> new HashMap<>());
                List<KvinTuple> values = propertyData.computeIfAbsent(tuple.property, (property) -> new ArrayList<>());
                values.add(tuple);
            }

            // converting tuples to json
            ObjectNode rootNode = mapper.createObjectNode();

            for (Map.Entry<URI, Map<URI, List<KvinTuple>>> data : groupedData.entrySet()) {
                ObjectNode predicateNode = mapper.createObjectNode();
                for (Map.Entry<URI, List<KvinTuple>> property : data.getValue().entrySet()) {
                    ArrayList<ObjectNode> objectList = new ArrayList<>();
                    for (KvinTuple tuple : property.getValue()) {
                        ObjectNode objectNode = mapper.createObjectNode();
                        objectNode.put("value", objectToJson(tuple.value));
                        objectNode.put("time", tuple.time);
                        objectNode.put("seqNr", tuple.seqNr);
                        objectList.add(objectNode);
                    }
                    predicateNode.set(property.getKey().toString(), mapper.createArrayNode().addAll(objectList));
                }
                rootNode.set(data.getKey().toString(), predicateNode);
            }

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);

            // sending post request to the remote endpoint
            // this.httpPost = this.httpPost != null ? this.httpPost : new HttpPost(this.hostEndpoint + "/linkedfactory/values");
            HttpPost httpPost = createHttpPost(this.hostEndpoint + "/linkedfactory/values");
            StringEntity requestPayload = new StringEntity(
                    json,
                    ContentType.APPLICATION_JSON
            );
            httpPost.setEntity(requestPayload);
            CloseableHttpResponse response = httpClient.execute(httpPost);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // The method will return the passed object converted to jackson JsonNode
    private JsonNode objectToJson(Object object) {
        JsonNode rootNode;

        if (object instanceof Record) {
            ObjectNode node = mapper.createObjectNode();
            node.set(((Record) object).getProperty().toString(), mapper.valueToTree(((Record) object).getValue()));
            rootNode = node;
        } else {
            rootNode = mapper.valueToTree(object);
        }

        //handling id -> @id conversion
        if (!rootNode.path("id").isMissingNode() && !rootNode.isTextual()) {
            ObjectNode node = (ObjectNode) rootNode;
            node.set("@id", rootNode.get("id"));
            node.remove("id");
        }
        return rootNode;
    }

    public HttpPost createHttpPost(String endpoint) {
        return new HttpPost(endpoint);
    }

    @Override
    public IExtendedIterator<KvinTuple> fetch(URI item, URI property, URI context, long limit) {
        IExtendedIterator<KvinTuple> kvinTuples = fetchInternal(item, property, context, null, null, limit, null, null);
        return kvinTuples;
    }

    @Override
    public IExtendedIterator<KvinTuple> fetch(URI item, URI property, URI context, long end, long begin, long limit, long interval, String op) {
        IExtendedIterator<KvinTuple> kvinTuples = fetchInternal(item, property, context, end, begin, limit, interval, op);
        return kvinTuples;
    }

    private IExtendedIterator<KvinTuple> fetchInternal(URI item, URI property, URI context, Long end, Long begin, Long limit, Long interval, String op) {
        ArrayList<KvinTuple> tuples = new ArrayList<>();

        try {
            // building url
            URIBuilder uriBuilder = new URIBuilder(this.hostEndpoint + "/linkedfactory/values");
            uriBuilder.setParameter("item", item.toString());
            uriBuilder.setParameter("property", property.toString());
            if (limit != null) uriBuilder.setParameter("limit", Long.toString(limit));
            if (end != null) uriBuilder.setParameter("to", Long.toString(end));
            if (begin != null) uriBuilder.setParameter("from", Long.toString(begin));
            if (interval != null) uriBuilder.setParameter("interval", Long.toString(interval));
            if (op != null) uriBuilder.setParameter("op", op);
            java.net.URI getRequestUri = uriBuilder.build();

            // sending get request to the endpoint
            HttpGet httpGet = createHttpGet(getRequestUri.toString());
            HttpResponse response = this.httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            String responseBody = EntityUtils.toString(entity);

            // converting json to kvin tuples
            JsonNode rootNode = mapper.readTree(responseBody);

            Iterator<Map.Entry<String, JsonNode>> data = rootNode.fields();
            while (data.hasNext()) {
                Map.Entry<String, JsonNode> itemNode = data.next();
                String itemName = itemNode.getKey();

                Iterator<Map.Entry<String, JsonNode>> propertyNode = itemNode.getValue().fields();
                while (propertyNode.hasNext()) {
                    Map.Entry<String, JsonNode> valueNode = propertyNode.next();
                    String propertyName = valueNode.getKey();

                    JsonNode valueArray = valueNode.getValue();
                    for (JsonNode node : valueArray) {
                        Record value = new Record(URIs.createURI("value"), node.get("value"));
                        tuples.add(new KvinTuple(URIs.createURI(itemName), URIs.createURI(propertyName), null, Long.parseLong(node.get("time").toString()), value));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return WrappedIterator.create(tuples.iterator());
    }

    public HttpGet createHttpGet(String endpoint) {
        return new HttpGet(endpoint);
    }

    @Override
    public long delete(URI item, URI property, URI context, long end, long begin) {
        return 0;
    }

    @Override
    public boolean delete(URI item) {
        return false;
    }

    @Override
    public IExtendedIterator<URI> descendants(URI item) {
        IExtendedIterator<URI> descendants = descendantsInternal(item, null);
        return descendants;
    }

    @Override
    public IExtendedIterator<URI> descendants(URI item, long limit) {
        IExtendedIterator<URI> descendants = descendantsInternal(item, limit);
        return descendants;
    }

    private IExtendedIterator<URI> descendantsInternal(URI item, Long limit) {
        ArrayList<URI> descendants = new ArrayList<>();

        try {
            // building url
            URIBuilder uriBuilder = new URIBuilder(this.hostEndpoint + "/linkedfactory/**");
            uriBuilder.setParameter("item", item.toString());
            if (limit != null) uriBuilder.setParameter("limit", Long.toString(limit));
            java.net.URI getRequestUri = uriBuilder.build();

            // sending get request to the endpoint
            HttpGet httpGet = createHttpGet(getRequestUri.toString());
            HttpResponse response = this.httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            String responseBody = EntityUtils.toString(entity);

            // converting json to URI
            JsonNode rootNode = mapper.readTree(responseBody);

            if (rootNode.isArray()) {
                for (JsonNode node : rootNode) {
                    descendants.add(URIs.createURI(node.get("@id").asText()));
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return WrappedIterator.create(descendants.iterator());
    }

    @Override
    public IExtendedIterator<URI> properties(URI item) {
        ArrayList<URI> properties = new ArrayList<>();

        try {
            // building url
            URIBuilder uriBuilder = new URIBuilder(this.hostEndpoint + "/linkedfactory/properties");
            uriBuilder.setParameter("item", item.toString());
            java.net.URI getRequestUri = uriBuilder.build();

            // sending get request to the endpoint
            HttpGet httpGet = createHttpGet(getRequestUri.toString());
            HttpResponse response = this.httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            String responseBody = EntityUtils.toString(entity);

            // converting json to URI
            JsonNode rootNode = mapper.readTree(responseBody);

            if (rootNode.isArray()) {
                for (JsonNode node : rootNode) {
                    properties.add(URIs.createURI(node.get("@id").asText()));
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return WrappedIterator.create(properties.iterator());
    }


    @Override
    public long approximateSize(URI item, URI property, URI context, long end, long begin) {
        return 0;
    }

    @Override
    public void close() {
        try {
            this.httpClient.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
