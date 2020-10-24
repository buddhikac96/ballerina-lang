/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package io.ballerina.projects.repos;

import io.ballerina.projects.Module;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageOrg;
import io.ballerina.projects.Project;
import io.ballerina.projects.SemanticVersion;
import io.ballerina.projects.balo.BaloProject;
import io.ballerina.projects.environment.PackageLoadRequest;
import io.ballerina.projects.environment.Repository;
import io.ballerina.projects.utils.ProjectConstants;
import io.ballerina.projects.utils.ProjectUtils;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Package Repository stored in file system.
 * The structure of the repository is as bellow
 * - balo
 *     - org
 *         - package-name
 *             - version
 *                 - org-package-name-version-any.balo
 * - cache
 *     - org
 *         - package-name
 *             - version
 *                 - bir
 *                     - mod1.bir
 *                     - mod2.bir
 *                 - jar
 *                     - org-package-name-version.jar
 *
 * @since 2.0.0
 */
public class FileSystemRepository implements Repository {

    Path root;
    Path balo;
    Path cache;

    public FileSystemRepository(Path cacheDirectory) {
        this.root = cacheDirectory;
        this.balo = this.root.resolve(ProjectConstants.REPO_BALO_DIR_NAME);
        this.cache = this.root.resolve(ProjectConstants.REPO_CACHE_DIR_NAME);
        // todo check if the directories are readable
    }


    @Override
    public Optional<Package> getPackage(PackageLoadRequest packageLoadRequest) {
        // if version and org name is empty we add empty string so we return empty package anyway
        String packageName = packageLoadRequest.packageName().value();
        String orgName = packageLoadRequest.orgName().map(PackageOrg::value).orElse("");
        String version = packageLoadRequest.version().orElse(SemanticVersion.from("0.0.0")).toString();
        String baloName = ProjectUtils.getBaloName(orgName, packageName, version, null);

        Path baloPath = this.balo.resolve(orgName).resolve(packageName).resolve(version).resolve(baloName);

        if (!Files.exists(baloPath)) {
            return Optional.empty();
        }

        Project project = BaloProject.loadProject(baloPath, this);
        return Optional.of(project.currentPackage());
    }

    @Override
    public List<SemanticVersion> getPackageVersions(PackageLoadRequest packageLoadRequest) {
        // if version and org name is empty we add empty string so we return empty package anyway
        String packageName = packageLoadRequest.packageName().value();
        String orgName = packageLoadRequest.orgName().map(PackageOrg::value).orElse("");

        // Here we dont rely on directories we check for available balos
        String globFilePart = orgName + "-" + packageName + "-*.balo";
        String glob = "glob:**/" + orgName + "/" + packageName + "/*/" + globFilePart;
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher(glob);
        List<Path> versions = new ArrayList<>();
        try {
            Files.walkFileTree(balo.resolve(orgName).resolve(packageName)
                    , new SearchModules(pathMatcher, versions));
        } catch (IOException e) {
            // in any error we should report to the top
            throw new RuntimeException("Error while accessing Distribution cache: " + e.getMessage());
        }

        return pathToVersions(versions);
    }

    private List<SemanticVersion> pathToVersions(List<Path> versions) {
        return versions.stream()
                .map(path -> {
                    String version = Optional.ofNullable(path.getParent())
                            .map(parent -> parent.getFileName())
                            .map(file -> file.toString())
                            .orElse("0.0.0");
                    return SemanticVersion.from(version);
                })
                .collect(Collectors.toList());
    }

    @Override
    public byte[] getCachedBir(Module module) {
        Path birFilePath = getBirPath(module.packageInstance()).resolve(module.moduleName().toString()
                + ProjectConstants.BLANG_COMPILED_PKG_BIR_EXT);
        if (Files.exists(birFilePath)) {
            try {
                return FileUtils.readFileToByteArray(birFilePath.toFile());
            } catch (IOException e) {
                // todo log
            }
        }
        return new byte[0];
    }

    @Override
    public void cacheBir(Module module, byte[] bir) {
        Path birFilePath = getBirPath(module.packageInstance()).resolve(module.moduleName().toString()
                + ProjectConstants.BLANG_COMPILED_PKG_BIR_EXT);
        if (!Files.exists(birFilePath)) {
            try {
                FileUtils.writeByteArrayToFile(birFilePath.toFile(), bir);
            } catch (IOException e) {
                // todo log
            }
        }
    }

    @Override
    public Path getCachedJar(Module aPackage) {
        return null;
    }

    public Path getJarPath(Package aPackage) {
        String packageName = aPackage.packageName().value();
        String orgName = aPackage.packageOrg().toString();
        String version = aPackage.packageVersion().version().toString();
        return this.cache.resolve(orgName).resolve(packageName).resolve(version)
                .resolve(ProjectConstants.REPO_JAR_CACHE_NAME);
    }

    public Path getBirPath(Package aPackage) {
        String packageName = aPackage.packageName().value();
        String orgName = aPackage.packageOrg().toString();
        String version = aPackage.packageVersion().version().toString();
        return this.cache.resolve(orgName).resolve(packageName).resolve(version)
                .resolve(ProjectConstants.REPO_BIR_CACHE_NAME);
    }

    private static class SearchModules extends SimpleFileVisitor<Path> {

        private final PathMatcher pathMatcher;
        private final List<Path> versions;

        public SearchModules(PathMatcher pathMatcher, List<Path> versions) {
            this.pathMatcher = pathMatcher;
            this.versions = versions;
        }

        @Override
        public FileVisitResult visitFile(Path path,
                                         BasicFileAttributes attrs) throws IOException {
            if (pathMatcher.matches(path)) {
                versions.add(path);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc)
                throws IOException {
            return FileVisitResult.CONTINUE;
        }
    }
}