// index.js
import { ref } from "vue";
import axios from "axios";

function resolveBaseUrl() {
  const { protocol, hostname } = window.location;
  if (hostname === "localhost" || hostname === "127.0.0.1") {
    return "http://127.0.0.1:1992";
  }
  if (hostname === "chaos.hongjunwei.com") {
    return "http://chaos.hongjunwei.com:1992";
  }
  return `${protocol}//${hostname}:1992`;
}

const baseUrl = resolveBaseUrl();
export function useHttp() {
  const loading = ref(false);

  async function get(url) {
    loading.value = true;
    const instance = axios.create({
      baseURL: baseUrl
    });
    try {
      const response = await instance.get(url);
      return response.data;
    } finally {
      loading.value = false;
    }
  }
  async function post(url, data) {
    loading.value = true;
    const instance = axios.create({
      baseURL:baseUrl
    });
    try {
      const response = await instance.post(url, data);
      return response.data;
    } finally {
      loading.value = false;
    }
  }

  function listenSSE(url, onMessage, options = {}) {
    const eventSource = new EventSource(
        baseUrl + url
    );

    eventSource.onopen = () => {
      if (options.onOpen) {
        options.onOpen();
      }
    };

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        onMessage(data);
      } catch (error) {
        onMessage(event.data);
      }
    };

    eventSource.onerror = (error) => {
      if (options.onError) {
        options.onError(error);
      } else {
        onMessage({ error: 'SSE 连接错误' });
      }
      if (options.closeOnError !== false) {
        eventSource.close();
      }
    };

    return () => {
      eventSource.close();
    };
  }
  return {
    loading,
    get,
    listenSSE,
    post,
  };
}
