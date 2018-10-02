import ballerina/internal;
import ballerina/io;
import ballerina/mime;
import ballerina/http;

@final int MAX_INT_VALUE = 2147483647;
@final string VERSION_REGEX = "(\\d+\\.)(\\d+\\.)(\\d+)";
DefaultLogger logger;

# This object denotes the default logger object used when pulling a package directly.
#
# + offset - Offset from the terminal width.
type DefaultLogger object {
    int offset = 0;
    function formatLog(string msg) returns string {
        return msg;
    }
};

# This object denotes the build logger object used when pulling a package while building.
#
# + offset - Offset from the terminal width.
type BuildLogger object {
    int offset = 10;
    function formatLog(string msg) returns string {
        return "\t" + msg;
    }
};

# This function pulls a package from ballerina central.
#
# + definedEndpoint - Endpoint defined with the proxy configurations
# + url - Url to be invoked
# + dirPath - Path of the directory to save the pulled package
# + pkgPath - Package path
# + fileSeparator - File separator based on the operating system
# + terminalWidth - Width of the terminal
# + versionRange - Supported version range
function pullPackage (http:Client definedEndpoint, string url, string dirPath, string pkgPath, string fileSeparator,
                      string terminalWidth, string versionRange) {
    endpoint http:Client httpEndpoint = definedEndpoint;
    string fullPkgPath = pkgPath;
    string destDirPath = dirPath;
    http:Request req = new;
    req.addHeader("Accept-Encoding", "identity");

    http:Response httpResponse = new;
    var result = httpEndpoint->get(untaint versionRange, message = req);

    match result {
        http:Response response => httpResponse = response;
        error e => {
            io:println(logger.formatLog("connection to the remote host failed : " + e.message));
            return;
        }
    }

    http:Response res = new;
    string statusCode = <string>httpResponse.statusCode;
    if (statusCode.hasPrefix("5")) {
        io:println(logger.formatLog("remote registry failed for url :" + url));
    } else if (statusCode != "200") {
        var jsonResponse = httpResponse.getJsonPayload();
        match jsonResponse {
            json resp => {
                io:println(logger.formatLog(resp.message.toString()));
            }
            error err => {
                io:println(logger.formatLog("error occurred when pulling the package"));
            }
        }
    } else {
        string contentLengthHeader;
        int pkgSize = MAX_INT_VALUE;

        if (httpResponse.hasHeader("content-length")) {
            contentLengthHeader = httpResponse.getHeader("content-length");
            pkgSize = check <int>contentLengthHeader;
        } else {
            io:println(logger.formatLog("warning: package size information is missing from remote repository"));
        }

        io:ReadableByteChannel sourceChannel = check (httpResponse.getByteChannel());

        string resolvedURI = httpResponse.resolvedRequestedURI;
        if (resolvedURI == "") {
            resolvedURI = url;
        }

        string[] uriParts = resolvedURI.split("/");
        string pkgVersion = uriParts[lengthof uriParts - 2];
        boolean valid = check pkgVersion.matches(VERSION_REGEX);

        if (valid) {
            string pkgName = fullPkgPath.substring(fullPkgPath.lastIndexOf("/") + 1, fullPkgPath.length());
            string archiveFileName = pkgName + ".zip";

            fullPkgPath = fullPkgPath + ":" + pkgVersion;
            destDirPath = destDirPath + fileSeparator + pkgVersion;
            string destArchivePath = destDirPath + fileSeparator + archiveFileName;

            if (!createDirectories(destDirPath)) {
                internal:Path pkgArchivePath = new(destArchivePath);
                if (pkgArchivePath.exists()){
                    io:println(logger.formatLog("package already exists in the home repository"));
                    return;
                }
            }

            io:WritableByteChannel wch = io:openWritableFile(untaint destArchivePath);

            string toAndFrom = " [central.ballerina.io -> home repo]";
            int rightMargin = 3;
            int width = (check <int>terminalWidth) - rightMargin;
            copy(pkgSize, sourceChannel, wch, fullPkgPath, toAndFrom, width);

            closeChannel(sourceChannel);
            closeChannel(wch);
        } else {
            io:println(logger.formatLog("package version could not be detected"));
        }
    }
}

# This function will invoke the method to pull the package.
# + args - Arguments passed
public function main(string... args){
    http:Client httpEndpoint;
    string host = args[4];
    string port = args[5];
    boolean isBuild = <boolean>args[10];
    if (isBuild) {
        logger = new BuildLogger();
    } else {
        logger = new DefaultLogger();
    }

    if (host != "" && port != "") {
        try {
            httpEndpoint = defineEndpointWithProxy(args[0], host, port, args[6], args[7]);
        } catch (error err) {
            io:println(logger.formatLog("failed to resolve host : " + host + " with port " + port));
            return;
        }
    } else  if (host != "" || port != "") {
        io:println(logger.formatLog("both host and port should be provided to enable proxy"));
        return;
    } else {
        httpEndpoint = defineEndpointWithoutProxy(args[0]);
    }
    pullPackage(httpEndpoint, args[0], args[1], args[2], args[3], args[8], args[9]);
}

# This function defines an endpoint with proxy configurations.
#
# + url - URL to be invoked
# + hostname - Host name of the proxy
# + port - Port of the proxy
# + username - Username of the proxy
# + password - Password of the proxy
# + return - Endpoint defined
function defineEndpointWithProxy (string url, string hostname, string port, string username, string password) returns http:Client{
    endpoint http:Client httpEndpoint {
        url: url,
        secureSocket:{
            trustStore:{
                path: "${ballerina.home}/bre/security/ballerinaTruststore.p12",
                password: "ballerina"
            },
            verifyHostname: false,
            shareSession: true
        },
        followRedirects: { enabled: true, maxCount: 5 },
        proxy : getProxyConfigurations(hostname, port, username, password)
    };
    return httpEndpoint;
}

# This function defines an endpoint without proxy configurations.
#
# + url - URL to be invoked
# + return - Endpoint defined
function defineEndpointWithoutProxy (string url) returns http:Client{
    endpoint http:Client httpEndpoint {
        url: url,
        secureSocket:{
            trustStore:{
                path: "${ballerina.home}/bre/security/ballerinaTruststore.p12",
                password: "ballerina"
            },
            verifyHostname: false,
            shareSession: true
        },
        followRedirects: { enabled: true, maxCount: 5 }
    };
    return httpEndpoint;
}

# This function will read the bytes from the byte channel.
#
# + byteChannel - Byte channel
# + numberOfBytes - Number of bytes to be read
# + return - Read content as byte[] along with the number of bytes read.
function readBytes(io:ReadableByteChannel byteChannel, int numberOfBytes) returns (byte[], int) {
    byte[] bytes;
    int numberOfBytesRead;
    (bytes, numberOfBytesRead) = check (byteChannel.read(numberOfBytes));
    return (bytes, numberOfBytesRead);
}

# This function will write the bytes from the byte channel.
#
# + byteChannel - Byte channel
# + content - Content to be written as a byte[]
# + startOffset - Offset
# + return - number of bytes written.
function writeBytes(io:WritableByteChannel byteChannel, byte[] content, int startOffset) returns int {
    int numberOfBytesWritten = check (byteChannel.write(content, startOffset));
    return numberOfBytesWritten;
}

# This function will copy files from source to the destination path.
#
# + pkgSize - Size of the package pulled
# + src - Byte channel of the source file
# + dest - Byte channel of the destination folder
# + fullPkgPath - Full package path
# + toAndFrom - Pulled package details
# + width - Width of the terminal
function copy(int pkgSize, io:ReadableByteChannel src, io:WritableByteChannel dest,
              string fullPkgPath, string toAndFrom, int width) {
    int terminalWidth = width - logger.offset;
    int bytesChunk = 8;
    byte[] readContent;
    int readCount = -1;
    float totalCount = 0.0;
    int numberOfBytesWritten = 0;
    string noOfBytesRead;
    string equals = "==========";
    string tabspaces = "          ";
    boolean completed = false;
    int rightMargin = 5;
    int totalVal = 10;
    int startVal = 0;
    int rightpadLength = terminalWidth - equals.length() - tabspaces.length() - rightMargin;
    try {
        while (!completed) {
            (readContent, readCount) = readBytes(src, bytesChunk);
            if (readCount <= startVal) {
                completed = true;
            }
            if (dest != null) {
                numberOfBytesWritten = writeBytes(dest, readContent, startVal);
            }
            totalCount = totalCount + readCount;
            float percentage = totalCount / pkgSize;
            noOfBytesRead = totalCount + "/" + pkgSize;
            string bar = equals.substring(startVal, <int>(percentage * totalVal));
            string spaces = tabspaces.substring(startVal, totalVal - <int>(percentage * totalVal));
            string size = "[" + bar + ">" + spaces + "] " + <int>totalCount + "/" + pkgSize;
            string msg = truncateString(fullPkgPath + toAndFrom, terminalWidth - size.length());
            io:print("\r" + logger.formatLog(rightPad(msg, rightpadLength) + size));
        }
    } catch (error err) {
        io:println("");
    }
    io:println("\r" + logger.formatLog(rightPad(fullPkgPath + toAndFrom, terminalWidth)));
}

# This function adds the right pad.
#
# + logMsg - Log message to be printed
# + logMsgLength - Length of the log message
# + return - The log message to be printed after adding the right pad
function rightPad (string logMsg, int logMsgLength) returns (string) {
    string msg = logMsg;
    int length = logMsgLength;
    int i = -1;
    length = length - msg.length();
    string char = " ";
    while (i < length) {
        msg = msg + char;
        i = i + 1;
    }
    return msg;
}

# This function truncates the string.
#
# + text - String to be truncated
# + maxSize - Maximum size of the log message printed
# + return - Truncated string.
function truncateString (string text, int maxSize) returns (string) {
    int lengthOfText = text.length();
    if (lengthOfText > maxSize) {
        int endIndex = 3;
        if (maxSize > endIndex) {
            endIndex = maxSize - endIndex;
        }
        string truncatedStr = text.substring(0, endIndex);
        return truncatedStr + "…";
    }
    return text;
}

# This function creates directories.
#
# + directoryPath - Directory path to be created
# + return - If the directories were created or not
function createDirectories(string directoryPath) returns (boolean) {
    internal:Path dirPath = new(directoryPath);
    if (!dirPath.exists()){
        match dirPath.createDirectory() {
            () => {
                return true;
            }
            error => {
                return false;
            }
        }
    } else {
        return false;
    }
}

# This function will close the byte channel.
#
# + byteChannel - Byte channel to be closed
function closeChannel(io:ReadableByteChannel|io:WritableByteChannel byteChannel) {
    match byteChannel {
        io:ReadableByteChannel rc => {
            match rc.close() {
                error channelCloseError => {
                    io:println(logger.formatLog("Error occured while closing the channel: " +
                                channelCloseError.message));
                }
                () => return;
            }
        }
        io:WritableByteChannel wc => {
            match wc.close() {
                error channelCloseError => {
                    io:println(logger.formatLog("Error occured while closing the channel: " +
                                channelCloseError.message));
                }
                () => return;
            }
        }
    }

}

# This function sets the proxy configurations for the endpoint.
#
# + hostName - Host name of the proxy
# + port - Port of the proxy
# + username - Username of the proxy
# + password - Password of the proxy
# + return - Proxy configurations for the endpoint
function getProxyConfigurations(string hostName, string port, string username, string password) returns http:ProxyConfig {
    int portInt = check <int> port;
    http:ProxyConfig proxy = { host : hostName, port : portInt , userName: username, password : password };
    return proxy;
}
