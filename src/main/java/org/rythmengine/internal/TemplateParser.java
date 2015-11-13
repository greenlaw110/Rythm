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
package org.rythmengine.internal;

import org.apache.commons.lang3.StringUtils;
import org.rythmengine.RythmEngine;
import org.rythmengine.conf.RythmConfiguration;
import org.rythmengine.exception.FastRuntimeException;
import org.rythmengine.exception.ParseException;
import org.rythmengine.extension.ICodeType;
import org.rythmengine.internal.compiler.TemplateClass;
import org.rythmengine.internal.dialect.DialectManager;
import org.rythmengine.internal.parser.build_in.SectionParser;
import org.rythmengine.logger.ILogger;
import org.rythmengine.logger.Logger;
import org.rythmengine.resource.TemplateResourceManager;

import java.util.Locale;
import java.util.Stack;

public class TemplateParser implements IContext {
    private final static ILogger logger = Logger.get(TemplateParser.class);
    private final CodeBuilder cb;
    private final RythmEngine engine;
    private final RythmConfiguration conf;
    private final boolean compactMode;
    private String template;
    private int totalLines;
    int cursor = 0;

    public TemplateParser(CodeBuilder cb) {
        this.template = cb.template();
        totalLines = StringUtils.countMatches(template, "\n") + 1;
        this.cb = cb;
        this.engine = cb.engine();
        this.conf = this.engine.conf();
        this.compactMode = this.conf.compactModeEnabled();
        pushCodeType(cb.templateDefLang);
    }

    public static class ExitInstruction extends FastRuntimeException {
    }

    private static abstract class RewindableException extends ParseException {
        public RewindableException(IContext ctx, String msg, Object... args) {
            super(ctx.getEngine(), ctx.getTemplateClass(), ctx.currentLine(), msg, args);
        }
    }

    public static class NoFreeLoopException extends RewindableException {
        public NoFreeLoopException(IContext ctx) {
            super(ctx, "Free loop style (@for(;;)) not allowed in current dialect[%s]", ctx.getDialect());
        }
    }

    public static class ScriptingDisabledException extends RewindableException {
        public ScriptingDisabledException(IContext ctx) {
            super(ctx, "Scripting not allowed in current dialect[%s]", ctx.getDialect());
        }
    }

    public static class ComplexExpressionException extends RewindableException {
        public ComplexExpressionException(IContext ctx) {
            super(ctx, "Complex expression not allowed in current dialect[%s]", ctx.getDialect());
        }
    }
    
    public static class TypeDeclarationException extends RewindableException {
        public TypeDeclarationException(IContext ctx) {
            super(ctx, "Type declaration not allowed in current dialect[%s]", ctx.getDialect());
        }
    }

    void parse() {
        DialectManager dm = engine.dialectManager();
        while (true) {
            this.breakStack.clear();
            this.codeTypeStack.clear();
            this.pushCodeType(cb.templateDefLang);
            this.inBodyStack.clear();
            this.inBodyStack2.clear();
            this.compactStack.clear();
            this.continueStack.clear();
            this.localeStack.clear();
            this.insideDirectiveComment = false;
            this.blocks.clear();
            cursor = 0;
            cb.rewind();
            dm.beginParse(this);
            TemplateResourceManager.setUpTmpBlackList();
            try {
                TemplateTokenizer tt = new TemplateTokenizer(this);
                for (Token builder : tt) {
                    cb.addBuilder(builder);
                }
                dm.endParse(this);
                break;
            } catch (ExitInstruction e) {
                dm.endParse(this);
                break;
            } catch (RewindableException e) {
                dm.endParse(this);
                if (null != cb.requiredDialect) {
                    throw e;
                }
            } catch (RuntimeException e) {
                dm.endParse(this);
                throw e;
            }
        }
    }

    @Override
    public TemplateClass getTemplateClass() {
        return cb.getTemplateClass();
    }

    @Override
    public CodeBuilder getCodeBuilder() {
        return cb;
    }

    private IDialect dialect = null;

    public IDialect getDialect() {
        return dialect;
    }

    public void setDialect(IDialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public String getRemain() {
      String result="";
      if (template!=null) {
        result=cursor < template.length() ? template.substring(cursor) : "";  
      }
      return result;
    }

    @Override
    public int cursor() {
        return cursor;
    }

    @Override
    public boolean hasRemain() {
        return template!=null && cursor < template.length();
    }

    @Override
    public char peek() {
        if (!hasRemain()) return '\u0000';
        return template.charAt(cursor);
    }

    @Override
    public char pop() {
        if (!hasRemain()) throw new ArrayIndexOutOfBoundsException();
        char c = template.charAt(cursor);
        step(1);
        return c;
    }

    @Override
    public void step(int i) {
        cursor += i;
    }

    @Override
    public String getTemplateSource(int start, int end) {
        return template.substring(start, end);
    }

    private Stack<IBlockHandler> blocks = new Stack<IBlockHandler>();

    @Override
    public void openBlock(IBlockHandler bh) {
        bh.openBlock();
        blocks.push(bh);
    }

    @Override
    public IBlockHandler currentBlock() {
        return blocks.isEmpty() ? null : blocks.peek();
    }

    @Override
    public String closeBlock() throws ParseException {
        if (blocks.isEmpty())
            throw new ParseException(getEngine(), cb.getTemplateClass(), currentLine(), "No open block found");
        IBlockHandler bh = blocks.pop();
        return null == bh ? "" : bh.closeBlock();
    }

    @Override
    public String currentSection() {
        int len = blocks.size();
        for (int i = len - 1; i >= 0; --i) {
            IBlockHandler h = blocks.get(i);
            if (h instanceof SectionParser.SectionToken) {
                return ((SectionParser.SectionToken)h).section();
            }
        }
        return null;
    }

    @Override
    public int currentLine() {
        if (null == template) return -1; // for testing purpose only
        if (cursor >= template.length()) return totalLines;
        //return template.substring(0, cursor).split("(\\r\\n|\\n|\\r)").length;
        return StringUtils.countMatches(template.substring(0, cursor), "\n") + 1;
    }

    @Override
    public RythmEngine getEngine() {
        return engine;
    }

    @Override
    public boolean compactMode() {
        if (!compactStack.empty()) return compactStack.peek();
        return compactMode;
    }

    private Stack<Boolean> compactStack = new Stack<Boolean>();

    @Override
    public void pushCompact(Boolean compact) {
        compactStack.push(compact);
    }

    @Override
    public Boolean peekCompact() {
        if (compactStack.empty()) return null;
        return compactStack.peek();
    }

    @Override
    public Boolean popCompact() {
        if (compactStack.empty()) return null;
        return compactStack.pop();
    }

    private Stack<Break> breakStack = new Stack<Break>();

    @Override
    public void pushBreak(Break b) {
        breakStack.push(b);
    }

    @Override
    public Break peekBreak() {
        if (breakStack.empty()) return null;
        return breakStack.peek();
    }

    @Override
    public Break popBreak() {
        if (breakStack.empty()) return null;
        return breakStack.pop();
    }

    private Stack<Continue> continueStack = new Stack<Continue>();

    @Override
    public void pushContinue(Continue b) {
        continueStack.push(b);
    }

    @Override
    public Continue peekContinue() {
        if (continueStack.empty()) return null;
        return continueStack.peek();
    }

    @Override
    public Continue popContinue() {
        if (continueStack.empty()) return null;
        return continueStack.pop();
    }

    @Override
    public boolean insideBody() {
        if (!inBodyStack.empty()) return inBodyStack.peek();
        return false;
    }

    private Stack<Boolean> inBodyStack = new Stack<Boolean>();

    @Override
    public void pushInsideBody(Boolean b) {
        inBodyStack.push(b);
    }

    @Override
    public Boolean peekInsideBody() {
        if (inBodyStack.empty()) return false;
        return inBodyStack.peek();
    }

    @Override
    public Boolean popInsideBody() {
        if (inBodyStack.empty()) return null;
        return inBodyStack.pop();
    }

    @Override
    public boolean insideBody2() {
        if (!inBodyStack2.empty()) return inBodyStack2.peek();
        return false;
    }

    private Stack<Boolean> inBodyStack2 = new Stack<Boolean>();

    @Override
    public void pushInsideBody2(Boolean b) {
        inBodyStack2.push(b);
    }

    @Override
    public Boolean peekInsideBody2() {
        if (inBodyStack2.empty()) return false;
        return inBodyStack2.peek();
    }

    @Override
    public Boolean popInsideBody2() {
        if (inBodyStack2.empty()) return null;
        return inBodyStack2.pop();
    }

    private boolean insideDirectiveComment = false;

    @Override
    public boolean insideDirectiveComment() {
        return this.insideDirectiveComment;
    }

    @Override
    public void enterDirectiveComment() {
        insideDirectiveComment = true;
    }

    @Override
    public void leaveDirectiveComment() {
        insideDirectiveComment = false;
    }

    private Stack<ICodeType> codeTypeStack = new Stack<ICodeType>();

    @Override
    public ICodeType peekCodeType() {
        if (codeTypeStack.isEmpty()) return null;
        return codeTypeStack.peek();
    }

    @Override
    public void pushCodeType(ICodeType type) {
        ICodeType cur = peekCodeType();
        if (null != cur) {
            type.setParent(cur);
        }
        codeTypeStack.push(type);
    }

    @Override
    public ICodeType popCodeType() {
        ICodeType cur = peekCodeType();
        if (null == cur) {
            return null;
        }
        cur.setParent(null);
        codeTypeStack.pop();
        return cur;
    }

    private Stack<Locale> localeStack = new Stack<Locale>();

    @Override
    public Locale peekLocale() {
        return localeStack.isEmpty() ? null : localeStack.peek();
    }

    @Override
    public void pushLocale(Locale locale) {
        localeStack.push(locale);
    }

    @Override
    public Locale popLocale() {
        return localeStack.isEmpty() ? null : localeStack.pop();
    }

    public void shutdown() {
        dialect = null;
    }

    /* this constructor is just for testing purpose */
    private TemplateParser(String s) {
        template = s;
        totalLines = template.split("(\\r\\n|\\n|\\r)").length + 1;
        cb = null;
        engine = null;
        conf = null;
        compactMode = true;
    }

}
