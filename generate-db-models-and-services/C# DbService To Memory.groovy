import com.intellij.database.model.DasColumn
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.Case
import com.intellij.database.util.DasUtil
import org.apache.groovy.io.StringBuilderWriter

typeMapping = [
        (~/(?i)^bit$/)                                    : "bool",
        (~/(?i)^tinyint$/)                                : "byte",
        (~/(?i)^uniqueidentifier|uuid$/)                  : "Guid",
        (~/(?i)^int|integer|number$/)                     : "int",
        (~/(?i)^bigint$/)                                 : "long",
        (~/(?i)^varbinary|image$/)                        : "byte[]",
        (~/(?i)^double|float|real$/)                      : "double",
        (~/(?i)^decimal|money|numeric|smallmoney$/)       : "decimal",
        (~/(?i)^datetimeoffset$/)                         : "DateTimeOffset",
        (~/(?i)^datetime|datetime2|timestamp|date|time$/) : "DateTime",
        (~/(?i)^char$/)                                   : "char",
]

notNullableTypes = [ "string", "byte[]" ]

globalSbw = new StringBuilderWriter();

SELECTION.filter { it instanceof DasTable && it.getKind() == ObjectKind.TABLE }.each { generate(it as DasTable, globalSbw) }

CLIPBOARD.set(globalSbw.toString());

def generate(DasTable table, StringBuilderWriter sbw) {
    def className = csharpName(table.getName())
    def fields = calcFields(table)
    globalSbw.withPrintWriter { PrintWriter out -> generate(out, className, fields, table) }
}

def generate(PrintWriter out, String className, LinkedList<FieldInfo> fields, DasTable table) {
    def dtoName = "Db${className}";
    def tableName = table.getName();
    def namespaceName = table.getDasParent().name;
    def fullTableName = namespaceName == "dbo" ? tableName : "[${namespaceName}].[${tableName}]";

    // Service interface
    out.println "[GeneratedCode(\"DevWouter/jetbrains-rider-tools\", \"0.0.0\")]"
    out.println "public interface I${className}DbService"
    out.println "{"

    def primaryFields = fields.findAll { it.isPrimary }
    primaryFields.each() {
        // Param name should start with lower case
        def paramName = it.name[0].toLowerCase() + it.name.substring(1)
        out.println "    ${dtoName} GetBy${it.type == "Guid" ? "Guid" : "Id"}(${it.type} ${paramName});"
    }

    // Check if any is primary
    // Entities without a public
    if(primaryFields.size() > 0) {
        out.println "    int Insert(${dtoName} item);"
        out.println "    int Update(${dtoName} item);"
        out.println "    int Delete(${dtoName} item);"
    }

    fields.findAll{ it.isForeign }.each() {
        // Param name should start with lower case
        def paramName = it.name[0].toLowerCase() + it.name.substring(1) + "s"
        out.println "    ${dtoName}[] FindBy${it.name.capitalize()}(IEnumerable<${it.type}> ${paramName});"
    }

    out.println "}"

    // Service implementation
    out.println ""
    out.println "[GeneratedCode(\"DevWouter/jetbrains-rider-tools\", \"0.0.0\")]"
    out.println "public class ${className}DbService : I${className}DbService"
    out.println "{"
    out.println "    private readonly IDbConnection _dbConnection;"
    out.println ""
    out.println "    public ${className}DbService(IDbConnection dbConnection)"
    out.println "    {"
    out.println "        _dbConnection = dbConnection;"
    out.println "    }"

    primaryFields.each() {
        // Param name should start with lower case
        def paramName = it.name[0].toLowerCase() + it.name.substring(1)
        def name = it.type == "Guid" ? "Guid" : "Id"
        out.println ""
        out.println "    public ${dtoName} GetBy${name}(${it.type} ${paramName})"
        out.println "    {"
        out.println "         return _dbConnection.QuerySingle<${dtoName}>(\"SELECT * FROM ${fullTableName} WHERE ${it.colName}=@id\", new { id = ${paramName} });"
        out.println "    }"
    }

    if(primaryFields.size() > 0) {
        // Insert
        out.println ""
        out.println "    public int Insert(${dtoName} item)"
        out.println "    {"
        out.print(  "        var includePrimaryKey = ");

        for (i in 0..<primaryFields.size()) {
            if(i != 0) out.print(" && ")

            def it = primaryFields[i];
            out.print("item.${it.colName} != ")
            if(it.type == "Guid") out.print("Guid.Empty")
            else if(it.type == "int") out.print("0")
            else out.print("string.Empty")
        }

        out.println(";")
        out.println ""
        out.println "        if(includePrimaryKey)"
        out.println "        {"
        out.println "            return _dbConnection.Execute("
        out.println "                 \"INSERT INTO ${fullTableName} (${fields.collect{ it.colName }.join(", ")}) VALUES (${fields.collect{ "@" + it.colName }.join(", ")})\","
        out.println "                 item);"
        out.println "        }"
        out.println "        else"
        out.println "        {"
        out.println "            return _dbConnection.Execute("
        out.println "                 \"INSERT INTO ${fullTableName} (${fields.findAll{ !it.isPrimary }.collect{ it.colName }.join(", ")}) VALUES (${fields.findAll{ !it.isPrimary }.collect{ "@" + it.colName }.join(", ")})\","
        out.println "                 item);"
        out.println "        }"
        out.println "    }"

        // Update
        out.println ""
        out.println "    public int Update(${dtoName} item)"
        out.println "    {"
        out.println "        return _dbConnection.Execute("
        out.println "            \"UPDATE ${fullTableName} SET ${fields.findAll{ !it.isPrimary }.collect{ it.colName + " = @" + it.name }.join(", ")} WHERE ${primaryFields.collect{ it.colName + " = @" + it.name }.join(" AND ")}\","
        out.println "            item);"
        out.println "    }"

        // Delete
        out.println ""
        out.println "    public int Delete(${dtoName} item)"
        out.println "    {"
        out.println "        return _dbConnection.Execute("
        out.println "            \"DELETE FROM ${fullTableName} WHERE ${primaryFields.collect{ it.colName + " = @" + it.name }.join(" AND ")}\","
        out.println "            item);"
        out.println "    }"
    }

    fields.findAll{ it.isForeign }.each() {
        // Param name should start with lower case
        def paramName = it.name[0].toLowerCase() + it.name.substring(1) + "s"
        out.println ""
        out.println "    public ${dtoName}[] FindBy${it.name.capitalize()}(IEnumerable<${it.type}> ${paramName})"
        out.println "    {"
        out.println "        return ${paramName}.Batch(2000)"
        out.println "            .SelectMany(chunk => _dbConnection.Query<${dtoName}>("
        out.println "               \"SELECT * FROM ${fullTableName} WHERE ${it.colName} IN @chunk\","
        out.println "               new { chunk }"
        out.println "            )).ToArray();"
        out.println "    }"
    }
    out.println "}"
}

LinkedList<FieldInfo> calcFields(DasTable table) {
    def result = [] as LinkedList<FieldInfo>
    def columns = DasUtil.getColumns(table)
    for (DasColumn it in columns) {
        def spec = Case.LOWER.apply(it.getDasType().getSpecification())
        def typeStr = typeMapping.find { p, t -> p.matcher(spec).find() }?.value ?: "string"
        def nullable = it.isNotNull() || typeStr in notNullableTypes ? "" : "?"
        var fieldInfo = new FieldInfo();
        fieldInfo.colName = it.getName()
        fieldInfo.name = csharpName(it.getName())
        fieldInfo.isPrimary = DasUtil.isPrimary(it)
        fieldInfo.isForeign = DasUtil.isForeign(it)
        fieldInfo.type = typeStr + nullable

        result.add(fieldInfo)
    }

    return result
}

String csharpName(String str) {
    def terms = com.intellij.psi.codeStyle.NameUtil.splitNameIntoWords(str)
    return terms.collect { Case.LOWER.apply(it).capitalize() }
            .join("")
}

class FieldInfo {
    public boolean isPrimary
    public boolean isForeign
    public String colName
    public String name
    public String type
}