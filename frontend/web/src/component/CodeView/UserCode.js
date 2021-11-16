/* eslint-disable */
import React, { useState } from "react";
import SyntaxHighlighter from "react-syntax-highlighter";
import {
  githubGist,
  darcula,
} from "react-syntax-highlighter/dist/cjs/styles/hljs";

const UserCode = ({ user, codeList }) => {
  const [selected, setSelected] = useState(codeList[codeList.length - 1]);

  return (
    <div>
      <h1>{user.name}</h1>
      <SyntaxHighlighter
        className="code"
        language="java"
        style={githubGist}
        showLineNumbers
        wrapLongLines
      >
        {Buffer.from(selected.content, "base64").toString("utf-8")}
      </SyntaxHighlighter>
    </div>
  );
};

export default UserCode;
