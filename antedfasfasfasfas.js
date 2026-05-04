import React from 'react';
// 한 번에 여러 컴포넌트를 구조 분해 할당으로 가져옵니다.
import { Button, DatePicker, Space, Version } from 'antd';

const App = () => (
    <div style={{ padding: 24 }}>
        <h1>Ant Design 버전: {Version}</h1>
        <Space>
            <Button type="primary">주요 버튼</Button>
            <DatePicker placeholder="날짜 선택" />
        </Space>
    </div>
);

export default App;
