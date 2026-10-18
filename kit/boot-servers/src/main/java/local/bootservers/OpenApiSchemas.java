package local.bootservers;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider;
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory;
import com.jetbrains.jsonSchema.extension.SchemaType;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OpenAPI 3.0 · Swagger 2.0 사양 파일에 JSON 스키마를 붙여 YAML · JSON 편집기가 자동 완성 · 오류 표시를 하게 한다.
 * 스키마는 jar 에 넣어 두어 인터넷 없이 돈다.
 * shortcut: OpenAPI 3.1 은 스키마(draft 2020-12)를 붙이지 않는다. 3.1 파일이 흔해지면 3.1 스키마를 더한다.
 */
public final class OpenApiSchemas implements JsonSchemaProviderFactory, DumbAware {

    @Override
    public @NotNull List<JsonSchemaFileProvider> getProviders(@NotNull Project project) {
        return List.of(
                new Provider("OpenAPI 3.0", OpenApiFiles.OPENAPI_30, "/openapi-schemas/openapi-3.0.json"),
                new Provider("Swagger 2.0", OpenApiFiles.SWAGGER_2, "/openapi-schemas/swagger-2.0.json"));
    }

    private record Provider(String name, String version, String resource) implements JsonSchemaFileProvider {

        @Override
        public boolean isAvailable(@NotNull VirtualFile file) {
            if (!OpenApiEditorProvider.isSpec(file)) {
                return false;
            }
            try {
                byte[] bytes = file.contentsToByteArray();
                int n = Math.min(bytes.length, OpenApiFiles.HEAD_CHARS);
                return version.equals(OpenApiFiles.version(new String(bytes, 0, n, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public @NotNull String getName() {
            return name;
        }

        @Override
        public VirtualFile getSchemaFile() {
            return JsonSchemaProviderFactory.getResourceFile(OpenApiSchemas.class, resource);
        }

        @Override
        public @NotNull SchemaType getSchemaType() {
            return SchemaType.embeddedSchema;
        }
    }
}
