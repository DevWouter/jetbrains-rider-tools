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
    def className = "Db" + csharpName(table.getName())
    def fields = calcFields(table)
    def sbw = new StringBuilderWriter();
    globalSbw.withPrintWriter { out -> generate(out, className, fields) }
}

def generate(out, className, fields) {
    out.println "[GeneratedCode(\"DevWouter/jetbrains-rider-tools\", \"0.0.0\")]"
    out.println "public class $className"
    out.println "{"
    fields.each() {
        out.println "    public ${it.type} ${it.colName} { get; set; }"
    }
    out.println "}"
}

def calcFields(table) {
    DasUtil.getColumns(table).reduce([]) { fields, col ->
        def spec = Case.LOWER.apply(col.getDataType().getSpecification())
        def typeStr = typeMapping.find { p, t -> p.matcher(spec).find() }?.value ?: "string"
        def nullable = col.isNotNull() || typeStr in notNullableTypes ? "" : "?"
        fields += [[
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