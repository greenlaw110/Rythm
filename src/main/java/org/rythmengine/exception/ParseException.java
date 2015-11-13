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
package org.rythmengine.exception;

import org.rythmengine.RythmEngine;
import org.rythmengine.internal.RythmEvents;
import org.rythmengine.internal.compiler.TemplateClass;

public class ParseException extends RythmException {
  
    /**
     * constructor to directly use a message without trying to format
     * @param engine
     * @param tc
     * @param line
     * @param message
     */
    public ParseException(RythmEngine engine, TemplateClass tc, int line,String message) {
      super(engine,null,tc,-1,line,message);
    }

    public ParseException(RythmEngine engine, TemplateClass tc, int line, String message, Object... args) {
        this(engine, null, tc, line, message, args);
    }

    public ParseException(RythmEngine engine, Throwable cause, TemplateClass tc, int line, String message, Object... args) {
        super(engine, cause, tc, -1, line, String.format(message, args));
        RythmEvents.PARSE_FAILED.trigger(engine, tc);
    }

    @Override
    public String errorTitle() {
        return "Rythm parse error";
    }

    @Override
    public String errorDesc() {
        return String.format("The template[%s] cannot be parsed: <strong>%s</strong>", getTemplateName(), originalMessage.replace("<", "&lt;"));
    }
}
