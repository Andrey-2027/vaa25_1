package org.ipro.query;

import java.util.LinkedHashSet;
import java.util.Set;

/** Named references for adapters. Uses the constructor's lexer rather than regex substitution. */
public final class JpqlParameters {
    private JpqlParameters() {}
    public static Set<String> names(String jpql) {
        var result=new LinkedHashSet<String>();
        if(jpql!=null)for(var token:JpqlLexer.tokenize(jpql))
            if(token.type()==JpqlLexer.Type.WORD && token.value().startsWith(":"))result.add(token.value().substring(1));
        return Set.copyOf(result);
    }
    public static String rename(String text,java.util.Map<String,String> names) {
        var result=new StringBuilder();int copied=0;
        for(var token:JpqlLexer.tokenize(text))if(token.type()==JpqlLexer.Type.WORD && token.value().startsWith(":")) {
            String replacement=names.get(token.value().substring(1));if(replacement==null)continue;
            if(!replacement.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("Некорректное имя биндинга");
            result.append(text,copied,token.position()).append(':').append(replacement);copied=token.position()+token.value().length();
        }
        return result.append(text.substring(copied)).toString();
    }
}
