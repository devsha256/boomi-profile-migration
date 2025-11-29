# Boomi Profile Migration CLI

A lightweight Spring Boot command-line tool to convert **Boomi XML/JSON Profiles** into **XSD**, **JSON Schema**, or **sample data**.

## Build

```bash
mvn clean package
````

This produces:

```
target/boomi-profile-migration-0.1.0.jar
```

## Run

```bash
java -jar target/boomi-profile-migration-0.1.0.jar <operation> <input-file>
```

### Supported operations

| Operation        | Description                           |
| ---------------- | ------------------------------------- |
| `xml-to-xsd`     | Convert `<XMLProfile>` → XSD          |
| `xml-sample`     | Generate sample XML                   |
| `json-to-schema` | Convert `<JSONProfile>` → JSON Schema |
| `json-sample`    | Generate sample JSON                  |

### Examples

```bash
java -jar target/boomi-profile-migration-0.1.0.jar xml-to-xsd ./profile.xml
java -jar target/boomi-profile-migration-0.1.0.jar json-sample ./profile.json.xml
```

## Notes

* Input must be a Boomi component XML containing `<XMLProfile>` or `<JSONProfile>`.
* Output is printed to standard output.
