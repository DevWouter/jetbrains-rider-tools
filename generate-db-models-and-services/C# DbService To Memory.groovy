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

SELECTION.filter { it instanceof DasTable && it.getKind() == ObjectKind.TABLE }.each { generate(it) }

CLIPBOARD.set(globalSbw.toString());

def generate(table) {
    def className = csharpName(table.getName())
    def fields = calcFields(table)
    def sbw = new StringBuilderWriter();
    globalSbw.withPrintWriter { out -> generate(out, className, fields, table) }
}

def generate(out, className, fields, table) {
    def dtoName = "Db${className}";
    def tableName = table.getName();
    def namespaceName = table.getDasParent().name;
    def fullTableName = namespaceName == "dbo" ? tableName : "[${namespaceName}].[${tableName}]";
   
    // Service interface
    out.println "[GeneratedCode(\"DevWouter/jetbrains-rider-tools\", \"0.0.0\")]"
    out.println "public interface I${className}DbService"
    out.println "{"
    fields.findAll{ it.isPrimary }.each() {
        // Param name should start with lower case
        def paramName = it.name[0].toLowerCase() + it.name.substring(1)
        out.println "    ${dtoName} GetBy${it.type == "Guid" ? "Guid" : "Id"}(${it.type} ${paramName});"
    }
    
    // Check if any is primary
    if(fields.findAll{ it.isPrimary }.size() > 0) {
        out.println "    void Insert(${dtoName} item);"
        out.println "    void Update(${dtoName} item);"
        out.println "    void Delete(${dtoName} item);"
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

    fields.findAll{ it.isPrimary }.each() {
        // Param name should start with lower case
        def paramName = it.name[0].toLowerCase() + it.name.substring(1)
        def name = it.type == "Guid" ? "Guid" : "Id" 
        out.println ""
        out.println "    public ${dtoName} GetBy${name}(${it.type} ${paramName})"
        out.println "    {"
        out.println "         return _dbConnection.QuerySingle<${dtoName}>(\"SELECT * FROM ${fullTableName} WHERE ${it.colName}=@id\", new { id = ${paramName} });"
        out.println "    }"
    }
    
    if(fields.findAll{ it.isPrimary }.size() > 0) {
        // Insert
        out.println ""
        out.println "    public void Insert(${dtoName} item)"
        out.println "    {"
        out.println "        var includePrimaryKey = true;"
        fields.findAll{ it.isPrimary }.each() {
            out.println "            includePrimaryKey = includePrimaryKey && item.${it.colName} != ${it.type == "Guid" ? "Guid.Empty" : "0"};"
        }
        
        out.println ""
        out.println "        if(includePrimaryKey)"
        out.println "        {"
        out.println "            _dbConnection.Execute("
        out.println "                 \"INSERT INTO ${fullTableName} (${fields.collect{ it.colName }.join(", ")}) VALUES (${fields.collect{ "@" + it.colName }.join(", ")})\","
        out.println "                 item);"
        out.println "        }"
        out.println "        else"
        out.println "        {"
        out.println "            _dbConnection.Execute("
        out.println "                 \"INSERT INTO ${fullTableName} (${fields.findAll{ !it.isPrimary }.collect{ it.colName }.join(", ")}) VALUES (${fields.findAll{ !it.isPrimary }.collect{ "@" + it.colName }.join(", ")})\","
        out.println "                 item);"
        out.println "        }"
        out.println "    }"
        
        // Update
        out.println ""
        out.println "    public void Update(${dtoName} item)"
        out.println "    {"
        out.println "        _dbConnection.Execute("
        out.println "            \"UPDATE ${fullTableName} SET ${fields.findAll{ !it.isPrimary }.collect{ it.colName + " = @" + it.name }.join(", ")} WHERE ${fields.findAll{ it.isPrimary }.collect{ it.colName + " = @" + it.name }.join(" AND ")}\","
        out.println "            item);"
        out.println "    }"
        
        // Delete
        out.println ""
        out.println "    public void Delete(${dtoName} item)"
        out.println "    {"
        out.println "        _dbConnection.Execute("
        out.println "            \"DELETE FROM ${fullTableName} WHERE ${fields.findAll{ it.isPrimary }.collect{ it.colName + " = @" + it.name }.join(" AND ")}\","
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

def calcFields(table) {
    DasUtil.getColumns(table).reduce([]) { fields, col ->
        def spec = Case.LOWER.apply(col.getDataType().getSpecification())
        def typeStr = typeMapping.find { p, t -> p.matcher(spec).find() }?.value ?: "string"
        def nullable = col.isNotNull() || typeStr in notNullableTypes ? "" : "?"
        fields += [[
                           isPrimary: DasUtil.isPrimary(col),
                           isForeign: DasUtil.isForeign(col),
                           colName: col.getName(),
                           name : csharpName(col.getName()),
                           type : typeStr + nullable]]
    }
}

def csharpName(str) {
    com.intellij.psi.codeStyle.NameUtil.splitNameIntoWords(str)
            .collect { Case.LOWER.apply(it).capitalize() }
            .join("")
}