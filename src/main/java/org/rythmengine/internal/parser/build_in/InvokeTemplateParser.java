/* 
 * Copyright (C) 2013 The Rythm Engine project
 * Gelin Luo <greenlaw110(at)gmail.com>
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package org.rythmengine.internal.parser.build_in;

import com.stevesoft.pat.Regex;

import org.rythmengine.RythmEngine.TemplateTestResult;
import org.rythmengine.internal.*;
import org.rythmengine.internal.parser.CodeToken;
import org.rythmengine.internal.parser.ParserBase;
import org.rythmengine.internal.parser.Patterns;
import org.rythmengine.utils.Escape;
import org.rythmengine.utils.S;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse tag invocation:
 *
 * <p>@myApp.myTag(...) Note since this is also a pattern for expression parser, InvokeTagParser must
 * be put in front of expression parser</p>
 * 
 * <p>use @myApp.myTag(...)@ to indicate the params inside are dynamic expression</p>
 */
public class InvokeTemplateParser extends CaretParserFactoryBase {

    public static class ParameterDeclaration {
        public String nameDef;
        public String valDef;

        ParameterDeclaration(String name, String val, IContext ctx) {
            if (null != name) {
                if (name.startsWith("\"") || name.startsWith("'")) name = name.substring(1);
                if (name.endsWith("\"") || name.endsWith("'")) name = name.substring(0, name.length() - 1);
            }
            nameDef = name;
            valDef = Token.processRythmExpression(val, ctx);
            //System.out.println(String.format("%s : %s", name, val));
        }

        @Override
        public String toString() {
            return String.format("%s:%s", nameDef, valDef);
        }
    }

    public static class ParameterDeclarationList {
        public List<ParameterDeclaration> pl = new ArrayList<ParameterDeclaration>();

        void addParameterDeclaration(String nameDef, String valDef, IContext ctx) {
            pl.add(new ParameterDeclaration(nameDef, valDef, ctx));
        }

        @Override
        public String toString() {
            return pl.toString();
        }
    }

    static class InvokeTagToken extends CodeToken {
        protected boolean isDynamic = false;
        protected String tagName;
        private boolean enableCallback = false;
        ParameterDeclarationList params = new ParameterDeclarationList();
        protected boolean enableCache = false;
        protected String cacheDuration = null;
        protected String cacheArgs = null;
        protected Escape escape = null;
        protected boolean ignoreNonExistsTag = false;
        protected String assignTo = null;
        protected boolean assignToFinal = false;
        protected List<CodeBuilder.RenderArgDeclaration> argList = null;

        static InvokeTagToken dynamicTagToken(String tagName, String paramLine, String extLine, IContext context) {
            InvokeTagToken t = new InvokeTagToken(tagName, paramLine, extLine, context);
            t.isDynamic = true;
            return t;
        }

        static InvokeTagToken dynamicTagToken(String tagName, String paramLine, String extLine, IContext context, boolean enableCallback) {
            InvokeTagToken t = new InvokeTagToken(tagName, paramLine, extLine, context, enableCallback);
            t.isDynamic = true;
            return t;
        }

        InvokeTagToken(String tagName, String paramLine, String extLine, IContext context) {
            this(tagName, paramLine, extLine, context, false);
        }

        InvokeTagToken(String tagName, String paramLine, String extLine, IContext context, boolean enableCallback) {
            super(null, context);
            this.tagName = tagName;
            this.enableCallback = enableCallback;
            parseParams(paramLine);
            parseExtension(extLine);
        }

        /*
         * Parse line like (bar='c', foo=bar.length(), zee=component[foo], "hello")
         */
        private void parseParams(String line) {
            parseParams(line, params, ctx);
        }

        static void parseParams(String line, ParameterDeclarationList params, IContext ctx) {
            if (S.isEmpty(line)) return;
            // strip '(' and ')'
            line = line.trim();
            if (line.startsWith("(")) line = S.stripBrace(line);
            Regex r = new Regex("\\G(\\s*,\\s*)?((([a-zA-Z_][\\w$_]*)\\s*[=:]\\s*)?((?@())|'.'|(?@\"\")|[0-9\\.]+[l]?|[a-zA-Z_][a-zA-Z0-9_\\.]*(?@())*(?@[])*(?@())*(\\.[a-zA-Z][a-zA-Z0-9_\\.]*(?@())*(?@[])*(?@())*)*@?)|[_a-zA-Z][a-z_A-Z0-9]*@?)");
            line = line.replaceAll("^\\s+", ""); // allow line breaks in params
            line = S.strip(line, "{", "}");
            line = line.replaceAll("^\\s+", ""); // allow line breaks in params
            while (r.search(line)) {
                params.addParameterDeclaration(r.stringMatched(4), r.stringMatched(5), ctx);
            }
        }

        private void parseExtension(String line) {
            if (S.isEmpty(line)) {
                return;
            }
            Regex r = new Regex("\\G(\\.)([_a-zA-Z][_a-zA-Z0-9]*)((?@()))");
            while (r.search(line)) {
                String group = r.stringMatched();
                String extension = r.stringMatched(2);
                String param = S.stripBrace(r.stringMatched(3));
                if ("cache".equals(extension)) {
                    parseCache(param);
                } else if (extension.startsWith("escape")) {
                    if (S.empty(param)) {
                        param = extension.replace("escape", "");
                    }
                    parseEscape(param);
                } else if ("raw".equals(extension)) {
                    escape = Escape.RAW;
                } else if ("callback".equals(extension)) {
                    parseCallback(param);
                } else if ("ignoreNonExistsTag".equals(extension)) {
                    ignoreNonExistsTag = true;
                } else if ("assign".equals(extension)) {
                    parseAssign(param);
                    ctx.getCodeBuilder().removeNextLF = true;
                } else {
                    //TODO enable transformer apply to tag invocation result
                    raiseParseException(ctx, "Unknown template invocation extension: %s. Currently supported extension: cache, escape, raw, callback, ignoreNonExistsTag", extension);
                }
            }
        }

        private void parseCache(String param) {
            enableCache = true;
            String[] sa = param.split(",");
            if (sa.length > 0) {
                String s = sa[0];
                cacheDuration = S.isEmpty(s) ? "null" : s;
            } else {
                cacheDuration = "null";
            }
            // check if duration is valid
            CacheParser.validateDurationStr(cacheDuration, ctx);
            if (sa.length > 1) {
                cacheArgs = param.replaceFirst(cacheDuration, "");
            } else {
                cacheArgs = ", _plUUID";
            }
        }

        private void parseEscape(String param) {
            if (S.isEmpty(param)) {
                escape = Escape.HTML;
            } else {
                param = S.stripQuotation(param).trim();
                try {
                    escape = Escape.valueOf(param.toUpperCase());
                } catch (Exception e) {
                    raiseParseException(ctx, "Unknown escape type: %s. Supported escape: RAW, HTML(default), JAVA, JS, JSON, CSV, XML", param);
                }
            }
        }

        private void parseAssign(String param) {
            String[] sa = param.split(",");
            assignTo = S.stripQuotation(sa[0]);
            if (S.isEmpty(assignTo)) {
                raiseParseException(ctx, "assign extension needs a variable name");
            }
            if (Patterns.RESERVED.matches(assignTo)) {
                raiseParseException(ctx, "assign variable name is reserved: %s", assignTo);
            }
            if (sa.length > 1) {
                this.assignToFinal = Boolean.parseBoolean(sa[1].trim());
            }
        }

        private void parseCallback(String param) {
            if (!enableCallback) {
                raiseParseException(ctx, "callback extension only apply to tag invocation with body");
            }
            argList = ArgsParser.parseArgDeclaration(ctx.currentLine(), param);
        }

        private String cacheKey = null;

        protected String cacheKey() {
            if (null == cacheKey) {
                if (!isDynamic) {
                    cacheKey = "\"" + UUID.nameUUIDFromBytes(("_RYTHM_TAG_" + tagName + ctx.getTemplateClass().name()).getBytes()).toString() + "\"";
                } else {
                    cacheKey = "\"_RYTHM_TAG_\" + " + tagName + " + \"" + ctx.getTemplateClass().name() + "\"";
                }
            }
            return cacheKey;
        }

        protected boolean needsNewOut() {
            return (assignTo != null) || (escape != null) || enableCache;
        }

        @Override
        public void output() {
            if (assignTo != null) {
                if (assignToFinal) {
                    pt("Object ").p(assignTo).p("___ = null;");
                } else {
                    pt("Object ").p(assignTo).p(" = null;");
                }
                pline();
            }
            pline("{");
            ptline("org.rythmengine.template.ITag.__ParameterList _pl = null; ");
            if (params.pl.size() > 0) {
                ptline("_pl = new org.rythmengine.template.ITag.__ParameterList();");
                for (int i = 0; i < params.pl.size(); ++i) {
                    ParameterDeclaration pd = params.pl.get(i);
                    //if (i == 0 && pd.nameDef == null) pd.nameDef = "arg";
                    pt("_pl.add(\"").p(pd.nameDef == null ? "" : pd.nameDef).p("\",").p(pd.valDef).p(");");
                    pline();
                }
            }
            if (needsNewOut()) {
                ptline("Object _r_s = null;");
                if (enableCache) {
                    ptline("String _plUUID = null == _pl ? \"\" : _pl.toUUID();");
                    pt("_r_s = __engine().cached(").p(cacheKey()).p(cacheArgs).p(");");
                    pline();
                }
                ptline("if (null == _r_s) {");
                p2tline("StringBuilder sbOld = __getBuffer();");
                p2tline("StringBuilder sbNew = new StringBuilder();");
                p2tline("setSelfOut(sbNew);");
                if (ctx.peekInsideBody()) {
                    p2t("__invokeTag(").p(line).p(", ").p(tagName).p(", _pl, null, __self, ").p(ignoreNonExistsTag).p(");");
                } else {
                    p2t("__invokeTag(").p(line).p(", ").p(tagName).p(", _pl, ").p(ignoreNonExistsTag).p(");");
                }
                pline();
                p2tline("_r_s = sbNew.toString();");
                p2tline("setSelfOut(sbOld);");
                if (escape != null) {
                    p2tline(String.format("_r_s = org.rythmengine.utils.Escape.%s.apply(_r_s);", escape.name()));
                }
                if (enableCache) {
                    p2t("__engine().cache(").p(cacheKey()).p(", _r_s, ").p(cacheDuration).p(cacheArgs).p(");");
                    pline();
                }
                ptline("}");
                if (assignTo != null) {
                    if (assignToFinal) {
                        pt(assignTo).p("___ = _r_s;");
                    } else {
                        pt(assignTo).p(" = _r_s;");
                    }
                    pline();
                } else {
                    ptline("p(_r_s);");
                }
            } else {
                if (ctx.peekInsideBody()) {
                    p2t("__invokeTag(").p(line).p(", ").p(tagName).p(", _pl, null, __self, ").p(ignoreNonExistsTag).p(");");
                } else {
                    p2t("__invokeTag(").p(line).p(", ").p(tagName).p(", _pl, ").p(ignoreNonExistsTag).p(");");
                }
                pline();
            }
            pline("}");
            if (assignTo != null && assignToFinal) {
                p("final Object ").p(assignTo).p(" = ").p(assignTo).p("___;");
                pline();
            }
        }
    }

    static class InvokeTagWithBodyToken extends InvokeTagToken implements IBlockHandler {
        private String textListenerKey = UUID.randomUUID().toString();
        private StringBuilder tagBodyBuilder = new StringBuilder();
        private int startIndex = 0;
        private int endIndex = 0;
        private String key = null;

        InvokeTagWithBodyToken(String tagName, String paramLine, String extLine, IContext context) {
            super(tagName, paramLine, extLine, context, true);
            context.openBlock(this);
            startIndex = ctx.cursor();
        }

        private String cacheKey = null;

        @Override
        protected String cacheKey() {
            if (null == cacheKey) {
                if (!isDynamic) {
                    cacheKey = "\"" + UUID.nameUUIDFromBytes(("_RYTHM_TAG_" + tagName + key + ctx.getTemplateClass().name()).getBytes()).toString() + "\"";
                } else {
                    cacheKey = "\"_RYTHM_TAG_\" + " + tagName + " + \"" + key + "\"" + " + \"" + ctx.getTemplateClass().name() + "\"";
                }
            }
            return cacheKey;
        }

        @Override
        public void openBlock() {
            ctx.pushInsideBody2(true);
            CodeBuilder cb = ctx.getCodeBuilder();
            cb.addBuilder(new Token("", ctx) {
                @Override
                protected void output() {
                    ctx.pushInsideBody(true);
                    super.output();
                }
            });
        }

        @Override
        public void output() {
            if (assignTo != null) {
                ptline("Object ").p(assignTo).p(" = null;");
            }
            pline("{");
            ptline("org.rythmengine.template.ITag.__ParameterList _pl = null; ");
            if (params.pl.size() > 0) {
                ptline("_pl = new org.rythmengine.template.ITag.__ParameterList();");
                for (int i = 0; i < params.pl.size(); ++i) {
                    ParameterDeclaration pd = params.pl.get(i);
                    //if (i == 0 && pd.nameDef == null) pd.nameDef = "arg";
                    pt("_pl.add(\"").p(pd.nameDef == null ? "" : pd.nameDef).p("\",").p(pd.valDef).p(");");
                    pline();
                }
            }
            String curClassName = ctx.getCodeBuilder().includingClassName();
            if (needsNewOut()) {
                pline("Object _r_s = null;");
                if (enableCache) {
                    ptline("String _plUUID = null == _pl ? \"\" : _pl.toUUID();");
                    pt("_r_s = __engine().cached(").p(cacheKey()).p(cacheArgs).p(");");
                    pline();
                }
                ptline("if (null == _r_s) {");
                p2tline("StringBuilder sbOld = __getBuffer();");
                p2tline("StringBuilder sbNew = new StringBuilder();");
                p2tline("setSelfOut(sbNew);");
            }
            p2t("__invokeTag(").p(line).p(", ").p(tagName).p(", _pl,  new org.rythmengine.template.ITag.__Body(").p(curClassName).p(".this) {");
            pline();
            if (null != argList && !argList.isEmpty()) {
                buildBodyArgList(argList);
            }
            p3tline("@Override public void __setProperty(String name, Object val) {");
            p4tline("__setRenderArg(name, val);");
            p3tline("}");
            p3tline("@Override public Object __getProperty(String name) {");
            p4tline("return __getRenderArg(name); ");
            p3tline("}");
            p3tline("@Override protected void __setBodyArgByName(String __name, Object __val) {");
            if (null != argList && !argList.isEmpty()) {
                buildSetBodyArgByName(argList);
            }
            p3tline("}");
            p3tline("@Override protected void __setBodyArgByPos(int __pos, Object __val) {");
            if (null != argList && !argList.isEmpty()) {
                buildSetBodyArgByPos(argList);
            }
            p3tline("}");
            p3tline("@Override protected void __call() {");
        }

        private void buildBodyArgList(List<CodeBuilder.RenderArgDeclaration> al) {
            for (CodeBuilder.RenderArgDeclaration arg : al) {
                p3t("protected ").p(arg.type).p(" ").p(arg.name);
                if (null != arg.defVal) {
                    p("=").p(arg.defVal).p(";");
                } else {
                    p(";");
                }
                pline();
            }
        }

        private void buildSetBodyArgByName(List<CodeBuilder.RenderArgDeclaration> al) {
            for (CodeBuilder.RenderArgDeclaration arg : al) {
                p4t("if (\"").p(arg.name).p("\".equals(__name)) this.").p(arg.name).p(" = __safeCast(__val, ").p(arg.objectType()).pn(".class);");
                pline();
            }
        }

        private void buildSetBodyArgByPos(List<CodeBuilder.RenderArgDeclaration> al) {
            p4tline("int __p = 0;");
            for (CodeBuilder.RenderArgDeclaration arg : al) {
                p4t("if (__p++ == __pos) { \n\t\t\tObject v = __val; \n\t\t\t").p(arg.name).p(" = __safeCast(v, ").p(arg.objectType()).p(".class); \n\t\t}\n"); 
                pline();
            }
        }

        @Override
        public String closeBlock() {
            ctx.popInsideBody2();
            ctx.getCodeBuilder().addBuilder(new Token("", ctx) {
                @Override
                protected void output() {
                    ctx.popInsideBody();
                    super.output();
                }
            });
            if (!needsNewOut()) {
                if (ctx.peekInsideBody2()) {
                    return "\n\t\t}\n\t}, __self);\n}";
                } else {
                    return "\n\t\t}\n\t});\n}";
                }
            }
            if (enableCache) {
                endIndex = ctx.cursor();
                String body = ctx.getTemplateSource(startIndex, endIndex);
                key = UUID.nameUUIDFromBytes(body.getBytes()).toString();
            }
            StringBuilder sbOld = __getBuffer();
            StringBuilder sbNew = new StringBuilder();
            __setBuffer(sbNew);
            p3tline("}");
            if (ctx.peekInsideBody2()) {
                p2t("}, __self, ").p(ignoreNonExistsTag).p(");");
            } else {
                p2t("}, ").p(ignoreNonExistsTag).p(");");
            }
            pline();
            p2tline("_r_s = sbNew.toString();");
            p2tline("setSelfOut(sbOld);");
            if (escape != null) {
                p2tline(String.format("_r_s = org.rythmengine.utils.Escape.%s.apply(_r_s);", escape.name()));
            }
            if (enableCache) {
                p2t("__engine().cache(").p(cacheKey()).p(", _r_s, ").p(cacheDuration).p(cacheArgs).p(");");
                pline();
            }
            ptline("}");
            if (assignTo != null) {
                pt(assignTo).p(" = _r_s;");
                pline();
            } else {
                ptline("p(_r_s);");
            }
            p2tline("}");
            String s = sbNew.toString();
            __setBuffer(sbOld);

            return s;
        }
    }

    static final Pattern P_HEREDOC_SIMBOL = Pattern.compile("(\\s*<<).*", Pattern.DOTALL);
    static final Pattern P_STANDARD_BLOCK = Pattern.compile("(\\s*\\{).*", Pattern.DOTALL);

    @Override
    public IParser create(final IContext ctx) {
        return new ParserBase(ctx) {

            String testTag(String name) {
                TemplateTestResult testResult=engine_.testTemplate(name, ctx().getTemplateClass(), ctx.peekCodeType());
                if (testResult==null) 
                  return null;
                return testResult.getFullName();
            }

            @Override
            public Token go() {
                Regex r = new Regex(String.format(patternStr(), dialect().a()));
                if (!r.search(remain())) return null;
                String tagName = r.stringMatched(2);
                try {
                    tagName = testTag(tagName);
                } catch (NoClassDefFoundError e) {
                    raiseParseException("Error load tag class: " + e.getMessage() + "\nPossible cause: lower or upper case issue on windows platform");
                }
                if (null == tagName) return null;
                else tagName = new StringBuilder("\"").append(tagName).append("\"").toString();
                String s = r.stringMatched();
                ctx().step(s.length());
                s = remain();
                Matcher m0 = P_HEREDOC_SIMBOL.matcher(s);
                Matcher m1 = P_STANDARD_BLOCK.matcher(s);
                if (m0.matches()) {
                    ctx().step(m0.group(1).length());
                    return new InvokeTagWithBodyToken(tagName, r.stringMatched(3), r.stringMatched(4), ctx());
                } else if (m1.matches()) {
                    ctx().step(m1.group(1).length());
                    return new InvokeTagWithBodyToken(tagName, r.stringMatched(3), r.stringMatched(4), ctx());
                } else {
                    return new InvokeTagToken(tagName, r.stringMatched(3), r.stringMatched(4), ctx());
                }
            }
        };
    }


    private static String patternStr() {
        return "^(%s([_a-zA-Z][a-zA-Z$_\\.0-9]*)\\s*((?@()))((\\.([_a-zA-Z][_a-zA-Z0-9]*)((?@())))*))";
    }

}
