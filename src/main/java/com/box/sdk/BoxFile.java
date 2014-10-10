package com.box.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

/**
 * Represents an individual file on Box. This class can be used to download a file's contents, upload new versions, and
 * perform other common file operations (move, copy, delete, etc.).
 */
public class BoxFile extends BoxItem {
    private static final URLTemplate FILE_URL_TEMPLATE = new URLTemplate("files/%s");
    private static final URLTemplate CONTENT_URL_TEMPLATE = new URLTemplate("files/%s/content");
    private static final URLTemplate VERSIONS_URL_TEMPLATE = new URLTemplate("files/%s/versions");
    private static final URLTemplate COPY_URL_TEMPLATE = new URLTemplate("files/%s/copy");
    private static final int BUFFER_SIZE = 8192;

    private final URL fileURL;
    private final URL contentURL;

    /**
     * Constructs a BoxFile for a file with a given ID.
     * @param  api the API connection to be used by the file.
     * @param  id  the ID of the file.
     */
    public BoxFile(BoxAPIConnection api, String id) {
        super(api, id);

        this.fileURL = FILE_URL_TEMPLATE.build(this.getAPI().getBaseURL(), this.getID());
        this.contentURL = CONTENT_URL_TEMPLATE.build(this.getAPI().getBaseURL(), this.getID());
    }

    /**
     * Creates a new shared link for this file.
     *
     * <p>This method is a convenience method for manually creating a new shared link and applying it to this file with
     * {@link Info#setSharedLink}. You may want to create the shared link manually so that it can be updated along with
     * other changes to the file's info in a single network request, giving a boost to performance.</p>
     *
     * @param  access      the access level of the shared link.
     * @param  unshareDate the date and time at which the link will expire. Can be null to create a non-expiring link.
     * @param  permissions the permissions of the shared link. Can be null to use the default permissions.
     * @return             the created shared link.
     */
    public BoxSharedLink createSharedLink(BoxSharedLink.Access access, Date unshareDate,
        BoxSharedLink.Permissions permissions) {

        BoxSharedLink sharedLink = new BoxSharedLink();
        sharedLink.setAccess(access);

        if (unshareDate != null) {
            sharedLink.setUnsharedDate(unshareDate);
        }

        sharedLink.setPermissions(permissions);

        Info info = new Info();
        info.setSharedLink(sharedLink);

        this.updateInfo(info);
        return info.getSharedLink();
    }

    /**
     * Downloads the contents of this file to a given OutputStream.
     * @param output the stream to where the file will be written.
     */
    public void download(OutputStream output) {
        this.download(output, null);
    }

    /**
     * Downloads the contents of this file to a given OutputStream while reporting the progress to a ProgressListener.
     * @param output   the stream to where the file will be written.
     * @param listener a listener for monitoring the download's progress.
     */
    public void download(OutputStream output, ProgressListener listener) {
        BoxAPIRequest request = new BoxAPIRequest(this.getAPI(), this.contentURL, "GET");
        BoxAPIResponse response = request.send();
        InputStream input = response.getBody();
        if (listener != null) {
            input = new ProgressInputStream(input, listener, response.getContentLength());
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            int n = input.read(buffer);
            while (n != -1) {
                output.write(buffer, 0, n);
                n = input.read(buffer);
            }
        } catch (IOException e) {
            throw new BoxAPIException("Couldn't connect to the Box API due to a network error.", e);
        }

        response.disconnect();
    }

    /**
     * Copies this file to another folder.
     * @param  destination the destination folder.
     * @return             info about the copied file.
     */
    public BoxFile.Info copy(BoxFolder destination) {
        return this.copy(destination, null);
    }

    /**
     * Copies this file to another folder and gives it a new name. If the destination is the same folder as the file's
     * current parent, then newName must be a new, unique name.
     * @param  destination the destination folder.
     * @param  newName     a new name for the copied file.
     * @return             info about the copied file.
     */
    public BoxFile.Info copy(BoxFolder destination, String newName) {
        URL url = COPY_URL_TEMPLATE.build(this.getAPI().getBaseURL(), this.getID());

        JsonObject parent = new JsonObject();
        parent.add("id", destination.getID());

        JsonObject copyInfo = new JsonObject();
        copyInfo.add("parent", parent);
        if (newName != null) {
            copyInfo.add("name", newName);
        }

        BoxJSONRequest request = new BoxJSONRequest(this.getAPI(), url, "POST");
        request.setBody(copyInfo.toString());
        BoxJSONResponse response = (BoxJSONResponse) request.send();
        JsonObject responseJSON = JsonObject.readFrom(response.getJSON());
        BoxFile copiedFile = new BoxFile(this.getAPI(), responseJSON.get("id").asString());
        return copiedFile.new Info(responseJSON);
    }

    /**
     * Deletes this file by moving it to the trash.
     */
    public void delete() {
        BoxAPIRequest request = new BoxAPIRequest(this.getAPI(), this.fileURL, "DELETE");
        BoxAPIResponse response = request.send();
        response.disconnect();
    }

    /**
     * Gets additional information about this file.
     * @return info about this file.
     */
    public BoxFile.Info getInfo() {
        BoxAPIRequest request = new BoxAPIRequest(this.getAPI(), this.fileURL, "GET");
        BoxJSONResponse response = (BoxJSONResponse) request.send();
        return new Info(response.getJSON());
    }

    /**
     * Gets additional information about this file that's limited to a list of specified fields.
     * @param  fields the fields to retrieve.
     * @return        info about this file containing only the specified fields.
     */
    public BoxFile.Info getInfo(String... fields) {
        String queryString = new QueryStringBuilder().addFieldsParam(fields).toString();
        URL url = FILE_URL_TEMPLATE.buildWithQuery(this.getAPI().getBaseURL(), queryString, this.getID());

        BoxAPIRequest request = new BoxAPIRequest(this.getAPI(), url, "GET");
        BoxJSONResponse response = (BoxJSONResponse) request.send();
        return new Info(response.getJSON());
    }

    /**
     * Updates the information about this file with any info fields that have been modified locally.
     *
     * <p>The only fields that will be updated are the ones that have been modified locally. For example, the following
     * code won't update any information (or even send a network request) since none of the info's fields were
     * changed:</p>
     *
     * <pre>BoxFile file = new File(api, id);
     *BoxFile.Info info = file.getInfo();
     *file.updateInfo(info);</pre>
     *
     * @param info the updated info.
     */
    public void updateInfo(BoxFile.Info info) {
        URL url = FILE_URL_TEMPLATE.build(this.getAPI().getBaseURL(), this.getID());
        BoxJSONRequest request = new BoxJSONRequest(this.getAPI(), url, "PUT");
        request.setBody(info.getPendingChanges());
        BoxJSONResponse response = (BoxJSONResponse) request.send();
        JsonObject jsonObject = JsonObject.readFrom(response.getJSON());
        info.update(jsonObject);
    }

    /**
     * Gets any previous versions of this file. Note that only users with premium accounts will be able to retrieve
     * previous versions of their files.
     * @return a list of previous file versions.
     */
    public Collection<BoxFileVersion> getVersions() {
        URL url = VERSIONS_URL_TEMPLATE.build(this.getAPI().getBaseURL(), this.getID());
        BoxAPIRequest request = new BoxAPIRequest(this.getAPI(), url, "GET");
        BoxJSONResponse response = (BoxJSONResponse) request.send();

        JsonObject jsonObject = JsonObject.readFrom(response.getJSON());
        JsonArray entries = jsonObject.get("entries").asArray();
        Collection<BoxFileVersion> versions = new ArrayList<BoxFileVersion>();
        for (JsonValue entry : entries) {
            versions.add(new BoxFileVersion(this.getAPI(), entry.asObject(), this.getID()));
        }

        return versions;
    }

    /**
     * Uploads a new version of this file, replacing the current version. Note that only users with premium accounts
     * will be able to view and recover previous versions of the file.
     * @param fileContent a stream containing the new file contents.
     */
    public void uploadVersion(InputStream fileContent) {
        this.uploadVersion(fileContent, null);
    }

    /**
     * Uploads a new version of this file, replacing the current version. Note that only users with premium accounts
     * will be able to view and recover previous versions of the file.
     * @param fileContent a stream containing the new file contents.
     * @param modified    the date that the new version was modified.
     */
    public void uploadVersion(InputStream fileContent, Date modified) {
        this.uploadVersion(fileContent, modified, 0, null);
    }

    /**
     * Uploads a new version of this file, replacing the current version, while reporting the progress to a
     * ProgressListener. Note that only users with premium accounts will be able to view and recover previous versions
     * of the file.
     * @param fileContent a stream containing the new file contents.
     * @param modified    the date that the new version was modified.
     * @param fileSize    the size of the file used for determining the progress of the upload.
     * @param listener    a listener for monitoring the upload's progress.
     */
    public void uploadVersion(InputStream fileContent, Date modified, long fileSize, ProgressListener listener) {
        URL uploadURL = CONTENT_URL_TEMPLATE.build(this.getAPI().getBaseUploadURL(), this.getID());
        BoxMultipartRequest request = new BoxMultipartRequest(getAPI(), uploadURL);
        if (fileSize > 0) {
            request.setFile(fileContent, "", fileSize);
        } else {
            request.setFile(fileContent, "");
        }

        if (modified != null) {
            request.putField("content_modified_at", modified);
        }

        BoxAPIResponse response;
        if (listener == null) {
            response = request.send();
        } else {
            response = request.send(listener);
        }
        response.disconnect();
    }

    /**
     * Contains additional information about a BoxFile.
     */
    public class Info extends BoxItem.Info {
        private String sha1;

        /**
         * Constructs an empty Info object.
         */
        public Info() {
            super();
        }

        /**
         * Constructs an Info object by parsing information from a JSON string.
         * @param  json the JSON string to parse.
         */
        public Info(String json) {
            super(json);
        }

        /**
         * Constructs an Info object using an already parsed JSON object.
         * @param  jsonObject the parsed JSON object.
         */
        protected Info(JsonObject jsonObject) {
            super(jsonObject);
        }

        @Override
        public BoxFile getResource() {
            return BoxFile.this;
        }

        /**
         * Gets the SHA1 hash of the file.
         * @return the SHA1 hash of the file.
         */
        public String getSha1() {
            return this.sha1;
        }

        @Override
        protected void parseJSONMember(JsonObject.Member member) {
            super.parseJSONMember(member);

            String memberName = member.getName();
            switch (memberName) {
                case "sha1":
                    this.sha1 = member.getValue().asString();
                    break;
                default:
                    break;
            }
        }
    }
}
